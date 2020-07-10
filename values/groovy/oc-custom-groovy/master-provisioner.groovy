//only runs on CJOC
import com.cloudbees.masterprovisioning.kubernetes.KubernetesMasterProvisioning
import com.cloudbees.opscenter.server.casc.BundleStorage
import com.cloudbees.opscenter.server.model.ManagedMaster
import com.cloudbees.opscenter.server.model.OperationsCenter
import com.cloudbees.opscenter.server.properties.ConnectedMasterLicenseServerProperty
import hudson.ExtensionList
import io.fabric8.kubernetes.client.utils.Serialization
import jenkins.model.Jenkins
import org.apache.commons.io.FileUtils
import java.util.logging.Logger

def main() {
    String scriptName = "master-provisioner.groovy"
    Logger logger = Logger.getLogger(scriptName)
    logger.info("Starting master provisioning script.")

    if (OperationsCenter.getInstance().getConnectedMasters().size() == 0) {
        // pretty hacky, but we need to wait a few seconds when booting the CJOC the first time and
        // the heuristic of "no masters defined yet" is reasonable for determining that.
        // If you're running this from the script console or jenkins cli, and have no masters, you
        // can just comment out this line if you want to save 5 second of your life.
        logger.info("Sleeping for 5 seconds.")
        sleep(5000)
    }

    def masterDefinitions = new File("/var/jenkins_config/master-definitions/masters.yaml")
    def mastersYaml = masterDefinitions.text

    def yamlMapper = Serialization.yamlMapper()
    Map map = yamlMapper.readValue(mastersYaml, Map.class);

    map.masters.each() { masterName, masterDefinition ->
        logger.info("create/update of master:${masterName} beginning.")

        //Either update or create the mm with this config
        if (OperationsCenter.getInstance().getConnectedMasters().any { it?.getName() == masterName }) {
            updateMM(masterName, masterDefinition)
        } else {
            createMM(masterName, masterDefinition)
        }
        sleep(1000)
        logger.info("Finished creating master ${masterName}")
    }
    logger.info("Master Provisioning script finished.")
}
main()

private void createMM(String masterName, def masterDefinition) {
    def configuration = new KubernetesMasterProvisioning()

    String appIds = masterDefinition.appIds?.join(",")

    masterDefinition.provisioning.each { k, v ->
        configuration["${k}"] = v
    }
    configuration["envVars"] = "APP_IDS=${appIds}"
    configuration["yaml"] = getManagedMasterYamlToMerge(masterName)

    ManagedMaster master = Jenkins.instance.createProject(ManagedMaster.class, masterName)

    println "Set config..."
    master.setConfiguration(configuration)
    master.properties.replace(new ConnectedMasterLicenseServerProperty(null))

    println "Save..."
    master.save()

    println "Run onModified..."
    master.onModified()

    createOrUpdateCascBundle(masterName, masterDefinition.bundle)

    applyRbacAtMasterRoot(masterName)

    //ok, now we can actually boot this thing up
    println "Ensure master starts."

    def validActionSet = master.getValidActionSet()
    if (validActionSet.contains(ManagedMaster.Action.ACKNOWLEDGE_ERROR)) {
        master.acknowledgeErrorAction()
        sleep(500)
    }

    validActionSet = master.getValidActionSet()
    if (validActionSet.contains(ManagedMaster.Action.START)) {
        master.startAction();
        sleep(500)
    } else if (validActionSet.contains(ManagedMaster.Action.PROVISION_AND_START)) {
        master.provisionAndStartAction();
        sleep(500)
    } else {
        throw "Cannot start the master." as Throwable
    }
}

private void updateMM(String masterName, def masterDefinition) {
    println "Master with this name already exists. Updating it."
    ManagedMaster managedMaster = OperationsCenter.getInstance().getConnectedMasters().find { it.name == masterName } as ManagedMaster
    def currentConfiguration = managedMaster.configuration

    String appIds = masterDefinition.appIds?.join(",")

    masterDefinition.provisioning.each { k, v ->
        currentConfiguration["${k}"] = v
    }
    currentConfiguration["envVars"] = "APP_IDS=${appIds}"
    currentConfiguration["yaml"] = getManagedMasterYamlToMerge(masterName)

    managedMaster.configuration = currentConfiguration
    managedMaster.save()

    applyRbacAtMasterRoot(masterName)

    createOrUpdateCascBundle(masterName, masterDefinition.bundle)

    //todo: check if this action can be taken first
    managedMaster.restartAction(false)
}


//TODO: here is where you should apply rbac to the master, if needed, since we are currently within the context of the CJOC.
//      note that the reference arch this script intends that masters do not have admin access applied to them other
//      than what is inherited from cluster admins. All master-specific RBAC should be a part of what is  autoconfigured on
//      folders on the master
//
// this code works, just leave it commented until we're ready to use it
private void applyRbacAtMasterRoot(String masterName) {
//    def master = Jenkins.instance.getAllItems().find { it.name.equals(masterName) }
//
//    String internalGroupName = "j-GroupAlpha"
//    String externalGroupName = "GroupAlpha"
//    String roleName = "develop"
//
//    GroupContainer container = GroupContainerLocator.locate(master)
//    Group group = new Group(container, internalGroupName)
//    group.doAddMember(externalGroupName)
//    group.doGrantRole(roleName, 0, Boolean.TRUE)
//    container.addGroup(group)
}

//instantiate a bundle for the master
private void createOrUpdateCascBundle(String masterName, def bundle) {
    def destinationDir = new File("/var/jenkins_home/jcasc-bundles-store/${masterName}")
    def destinationBundleDotYamlFileHandle = new File("/var/jenkins_home/jcasc-bundles-store/${masterName}/bundle.yaml")
    int bundleVersion = 1

    if (destinationDir.exists()) {
        println "Bundle with this masterName already exists. Updating it..."

        bundleVersion = getExistingBundleVersion(destinationBundleDotYamlFileHandle) + 1

        createOrUpdateBundle(destinationDir, bundle, masterName, bundleVersion, destinationBundleDotYamlFileHandle)
        setBundleSecurity(masterName, false)
    } else {
        println "Bundle with this masterName does not exist. Creating it..."

        createEntryInSecurityFile(masterName)

        createOrUpdateBundle(destinationDir, bundle, masterName, bundleVersion, destinationBundleDotYamlFileHandle)
        setBundleSecurity(masterName, true)
    }
}

private void setBundleSecurity(String masterName, boolean regenerateBundleToken) {
    sleep(500)
    ExtensionList.lookupSingleton(BundleStorage.class).initialize()
    BundleStorage.AccessControl accessControl = ExtensionList.lookupSingleton(BundleStorage.class).getAccessControl()
    accessControl.updateMasterPath(masterName, masterName)
    if(regenerateBundleToken) {
        accessControl.regenerate(masterName)
    }
}

private void createOrUpdateBundle(File destinationDir, def bundle, String masterName, int bundleVersion, File bundleYamlFileHandle) {
    createOrUpdateBundleDir(destinationDir, bundle)
    writeBundleYamlFile(masterName, bundleVersion, bundleYamlFileHandle)
}

private void createOrUpdateBundleDir(File destinationDir, def bundle) {
    if (destinationDir.exists()) {
        FileUtils.forceDelete(destinationDir)
    }
    FileUtils.forceMkdir(destinationDir)

    def destinationDirPath = destinationDir.getAbsolutePath()

    def yamlMapper = Serialization.yamlMapper()
    def jcasc = yamlMapper.writeValueAsString(bundle.jcasc)?.replace("---", "")?.trim()
    def plugins = yamlMapper.writeValueAsString([plugins: bundle.plugins])?.replace("---", "")?.trim()
    def pluginCatalog = yamlMapper.writeValueAsString(bundle.pluginCatalog)?.replace("---", "")?.trim()

    if (jcasc == "null") {
        jcasc = ""
    }
    if (plugins == "null") {
        plugins = ""
    }
    if (pluginCatalog == "null") {
        pluginCatalog = ""
    }

    File jenkinsYaml = new File(destinationDirPath + "/jenkins.yaml")
    jenkinsYaml.createNewFile()
    jenkinsYaml.text = jcasc

    File pluginsYaml = new File(destinationDirPath + "/plugins.yaml")
    pluginsYaml.createNewFile()
    pluginsYaml.text = plugins

    File pluginCatalogYaml = new File(destinationDirPath + "/plugin-catalog.yaml")
    pluginCatalogYaml.createNewFile()
    pluginCatalogYaml.text = pluginCatalog
}

private void createEntryInSecurityFile(String masterName) {
    //create entry in security file; only the first time we create a bundle and never again. Hopefully this goes
    //away in future versions of CB CasC
    String newerEntry = """\n<entry>
      <string>${masterName}</string>
      <com.cloudbees.opscenter.server.casc.BundleStorage_-AccessControlEntry>
        <secret>{aGVyZWJlZHJhZ29ucwo=}</secret>
        <masterPath>${masterName}</masterPath>
      </com.cloudbees.opscenter.server.casc.BundleStorage_-AccessControlEntry>
    </entry>\n"""

    def cascSecFilePath = "/var/jenkins_home/core-casc-security.xml"
    def cascSecFile = new File(cascSecFilePath)
    String cascSecFileContents = cascSecFile.getText('UTF-8')

    if (cascSecFileContents.contains("<entries/>")) {
        cascSecFileContents = cascSecFileContents.replace("<entries/>", "<entries></entries>")
    } else {
        cascSecFileContents = cascSecFileContents.replace("<entries>", "<entries>${newerEntry}")
    }
    cascSecFile.write(cascSecFileContents)
}

private void writeBundleYamlFile(String masterName, int bundleVersion, bundleYamlFileHandle) {
    bundleYamlFileHandle.write(
            """id: '${masterName}'
version: '${bundleVersion}'
apiVersion: '1'
description: 'Bundle for ${masterName}'
plugins:
- 'plugins.yaml'
jcasc:
- 'jenkins.yaml'
catalog:
- 'plugin-catalog.yaml'
"""
    )
}

private int getExistingBundleVersion(File bundleYamlFileHandle) {
    def versionLine = bundleYamlFileHandle.readLines().find { it.startsWith("version") }
    String version = versionLine.replace("version:", "").replace(" ", "").replace("'", "").replace('"', '')
    return version as int
}

static def getManagedMasterYamlToMerge(String masterName) {
    return """kind: StatefulSet
spec:
  template:
    spec:
      containers:
      - name: jenkins
        volumeMounts:
        - name: mm-custom-groovy
          mountPath: /var/jenkins_config/configure-jenkins.groovy.d/
      volumes:
      - name: mm-custom-groovy
        configMap:
          defaultMode: 420
          name: mm-custom-groovy
          apiVersion: "apps/v1"
---
kind: Service
metadata:
  annotations:
    prometheus.io/scheme: 'http'
    prometheus.io/path: '/${masterName}/prometheus'
    prometheus.io/port: '8080'
    prometheus.io/scrape: 'true'
"""
}

