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


String scriptName = "master-provisioner.groovy"
Logger logger = Logger.getLogger(scriptName)
logger.info("Starting master provisioning script.")

if(OperationsCenter.getInstance().getConnectedMasters().size()==0) {
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

String yamlToMerge = """kind: StatefulSet
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
"""
map.masters.each() {
    logger.info("Creating master ${it.name}")
    String masterName = it.name
    String bundleTemplate = it.cascBundleTemplate
    String appIds = it.appIds?.join(",")

    Map props = [
//    allowExternalAgents: false, //boolean
//    clusterEndpointId: "default", //String
//    cpus: 1.0, //Double
disk   : it.disk, //Integer //
//    domain: "readYaml-custom-domain-1", //String
envVars: "APP_IDS=${appIds}", //String
//    fsGroup: "1000", //String
//    image: "custom-image-name", //String -- set this up in Operations Center Docker Image configuration
//    javaOptions: "${KubernetesMasterProvisioning.JAVA_OPTIONS} -Dadditional.option", //String
//    jenkinsOptions:"", //String
//    kubernetesInternalDomain: "cluster.local", //String
//    livenessInitialDelaySeconds: 300, //Integer
//    livenessPeriodSeconds: 10, //Integer
//    livenessTimeoutSeconds: 10, //Integer
memory : it.memory, //Integer
//    namespace: null, //String
//    nodeSelectors: null, //String
//    ratio: 0.7, //Double
//    storageClassName: null, //String
//    systemProperties:"", //String
//    terminationGracePeriodSeconds: 1200, //Integer
yaml   : yamlToMerge //String
    ]

//Either update or create the mm with this config
    if (OperationsCenter.getInstance().getConnectedMasters().any { it?.getName() == masterName }) {
        updateMM(masterName, props, bundleTemplate)
    } else {
        createMM(masterName, props, bundleTemplate)
    }
    sleep(1000)
    logger.info("Finished creating master ${it.name}")
}
logger.info("Master Provisioning script finished.")

private void createMM(String masterName, LinkedHashMap<String, Serializable> props, String bundleTemplate) {
    def configuration = new KubernetesMasterProvisioning()

    props.each { key, value ->
        configuration."$key" = value
    }

    ManagedMaster master = Jenkins.instance.createProject(ManagedMaster.class, masterName)

    println "Set config..."
    master.setConfiguration(configuration)
    master.properties.replace(new ConnectedMasterLicenseServerProperty(null))

    println "Save..."
    master.save()

    println "Run onModified..."
    master.onModified()

    createOrUpdateCascBundle(masterName, bundleTemplate)

    applyRbacAtMasterRoot(masterName)

    //ok, now we can actually boot this thing up
    println "Ensure master starts."

    def validActionSet = master.getValidActionSet()
    if (validActionSet.contains(ManagedMaster.Action.ACKNOWLEDGE_ERROR)) {
        master.acknowledgeErrorAction()
        sleep(500)
    }

    validActionSet = master.getValidActionSet()
    if(validActionSet.contains(ManagedMaster.Action.START)) {
        master.startAction();
        sleep(500)
    } else if(validActionSet.contains(ManagedMaster.Action.PROVISION_AND_START)) {
        master.provisionAndStartAction();
        sleep(500)
    } else {
        throw "Cannot start the master." as Throwable
    }
}

private void updateMM(String masterName, LinkedHashMap<String, Serializable> props, String bundleTemplate) {
    println "Master with this name already exists. Updating it."
    ManagedMaster managedMaster = OperationsCenter.getInstance().getConnectedMasters().find { it.name == masterName } as ManagedMaster
    def configuration = managedMaster.configuration
    props.each { key, value ->
        configuration."$key" = value
    }
    managedMaster.configuration = configuration
    managedMaster.save()

    applyRbacAtMasterRoot(masterName)

    createOrUpdateCascBundle(masterName, bundleTemplate)

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


//instantiate a copy of the bundle template with the master name
private void createOrUpdateCascBundle(String masterName, String bundleTemplate) {
    def bundleTemplateFileHandle = new File("/var/jenkins_config/bundle-templates/${bundleTemplate}.yaml")
    def destinationDir = new File("/var/jenkins_home/jcasc-bundles-store/${masterName}")
    def bundleYamlFileHandle = new File("/var/jenkins_home/jcasc-bundles-store/${masterName}/bundle.yaml")
    int bundleVersion = 1

    if (destinationDir.exists()) {
        println "Bundle with this masterName already exists. Updating it..."
        bundleVersion = getCurrentBundleVersion(bundleYamlFileHandle) + 1

        createOrUpdateBundleDir(destinationDir, bundleTemplateFileHandle)
        writeBundleYamlFile(masterName, bundleTemplate, bundleVersion, bundleYamlFileHandle)

        //ensure our changes on disk are pulled in
        sleep(500)
        ExtensionList.lookupSingleton(BundleStorage.class).initialize()
        BundleStorage.AccessControl accessControl = ExtensionList.lookupSingleton(BundleStorage.class).getAccessControl()
        accessControl.updateMasterPath(masterName, masterName)
    } else {
        println "Bundle with this masterName does not exist. Creating it..."

        createEntryInSecurityFile(masterName)
        createOrUpdateBundle(destinationDir, bundleTemplateFileHandle, masterName, bundleTemplate, bundleVersion, bundleYamlFileHandle)

        sleep(500)
        ExtensionList.lookupSingleton(BundleStorage.class).initialize()
        BundleStorage.AccessControl accessControl = ExtensionList.lookupSingleton(BundleStorage.class).getAccessControl()
        accessControl.updateMasterPath(masterName, masterName)
        accessControl.regenerate(masterName)
    }
}

private void createOrUpdateBundle(File destinationDir, File sourceFile, String masterName, String bundleTemplate, int bundleVersion, File bundleYamlFileHandle) {
    createOrUpdateBundleDir(destinationDir, sourceFile)
    writeBundleYamlFile(masterName, bundleTemplate, bundleVersion, bundleYamlFileHandle)
}

private void createOrUpdateBundleDir(File destinationDir, File bundleTemplateFileHandle) {
    if(destinationDir.exists()) {
        FileUtils.forceDelete(destinationDir)
    }
    FileUtils.forceMkdir(destinationDir)

    def yamlMapper = Serialization.yamlMapper()
    Map map = yamlMapper.readValue(bundleTemplateFileHandle.text, Map.class);

    def destinationDirPath = destinationDir.getAbsolutePath()

    def jcasc = yamlMapper.writeValueAsString(map.jcasc)?.replace("---","")?.trim()
    def plugins = yamlMapper.writeValueAsString([plugins: map.plugins])?.replace("---","")?.trim()
    def pluginCatalog = yamlMapper.writeValueAsString(map.pluginCatalog)?.replace("---","")?.trim()

    if(jcasc == "null") {
        jcasc=""
    }
    if(plugins == "null") {
        plugins=""
    }
    if(pluginCatalog == "null") {
        pluginCatalog=""
    }

    File jenkinsYaml = new File(destinationDirPath + "/jenkins.yaml")
    jenkinsYaml.createNewFile()
    jenkinsYaml.text=jcasc

    File pluginsYaml = new File(destinationDirPath + "/plugins.yaml")
    pluginsYaml.createNewFile()
    pluginsYaml.text=plugins

    File pluginCatalogYaml = new File(destinationDirPath + "/plugin-catalog.yaml")
    pluginCatalogYaml.createNewFile()
    pluginCatalogYaml.text=pluginCatalog
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

private void writeBundleYamlFile(String masterName, String bundleTemplate, int bundleVersion, bundleYamlFileHandle) {
    bundleYamlFileHandle.write(
            """id: '${masterName}'
version: '${bundleVersion}'
apiVersion: '1'
description: 'copy of ${bundleTemplate} for ${masterName}'
plugins:
- 'plugins.yaml'
jcasc:
- 'jenkins.yaml'
catalog:
- 'plugin-catalog.yaml'
"""
    )
}

private int getCurrentBundleVersion(File bundleYamlFileHandle) {
    def versionLine = bundleYamlFileHandle.readLines().find {it.startsWith("version")}
    String version = versionLine.replace("version:", "").replace(" ", "").replace("'","").replace('"','')
    return version as int
}
