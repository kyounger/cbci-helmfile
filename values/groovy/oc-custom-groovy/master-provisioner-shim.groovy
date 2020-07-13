//only runs on CJOC

import com.cloudbees.masterprovisioning.kubernetes.KubernetesMasterProvisioning
import com.cloudbees.opscenter.server.casc.BundleStorage
import com.cloudbees.opscenter.server.model.ManagedMaster
import com.cloudbees.opscenter.server.model.OperationsCenter
import com.cloudbees.opscenter.server.properties.ConnectedMasterLicenseServerProperty
import hudson.ExtensionList
import io.fabric8.kubernetes.client.utils.Serialization
import io.jenkins.cli.shaded.org.apache.commons.lang.StringUtils
import jenkins.model.Jenkins
import org.apache.commons.io.FileUtils

void main() {
    println("Master Provisioning Shim script -- started.\n")

    if (OperationsCenter.getInstance().getConnectedMasters().size() == 0) {
        // pretty hacky, but we need to wait a few seconds when booting the CJOC the first time and
        // the heuristic of "no masters defined yet" is reasonable for determining that.
        // If you're running this from the script console or jenkins cli, and have no masters, you
        // can just comment out this line if you want to save 5 second of your life.
        println("Sleeping for 5 seconds.")
        sleep(5000)
    }

    def masterDefinitions = new File("/var/jenkins_config/master-definitions/masters.yaml")
    def mastersYaml = masterDefinitions.text

    def yamlMapper = Serialization.yamlMapper()
    Map map = yamlMapper.readValue(mastersYaml, Map.class);

    map.masters.each() { masterName, masterDefinition ->
        println("Create/update of master '${masterName}' beginning.")

        //Either update or create the mm with this config
        if (OperationsCenter.getInstance().getConnectedMasters().any { it?.getName() == masterName }) {
            updateMM(masterName, masterDefinition)
        } else {
            createMM(masterName, masterDefinition)
        }
        sleep(1000)
        println("Finished with master '${masterName}'.\n")
    }
    println("Master Provisioning Shim script -- finished.\n")
}

main()

private void createMM(String masterName, def masterDefinition) {
    println "Master '${masterName}' does not exist yet. Creating it now."
    def configuration = new KubernetesMasterProvisioning()

    masterDefinition.provisioning.each { k, v ->
        configuration["${k}"] = v
    }

    ManagedMaster master = Jenkins.instance.createProject(ManagedMaster.class, masterName)

    master.setConfiguration(configuration)
    master.properties.replace(new ConnectedMasterLicenseServerProperty(null))

    master.save()

    master.onModified()

    createEntryInSecurityFile(masterName)
    createOrUpdateBundle(masterDefinition.bundle, masterName)
    setBundleSecurity(masterName, true)

    //applyRbacAtMasterRoot(masterName)

    writeStateYaml(masterDefinition, masterName)

    //ok, now we can actually boot this thing up
    println "Ensuring master '${masterName}' starts..."

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
    println "Master '${masterName}' already exists. Checking if definition has changed."
    boolean masterDefinitionIsChanged = false

    ManagedMaster managedMaster = OperationsCenter.getInstance().getConnectedMasters().find { it.name == masterName } as ManagedMaster

    def currentConfiguration = managedMaster.configuration
    masterDefinition.provisioning.each { k, v ->
        if (currentConfiguration["${k}"] != v) {
            currentConfiguration["${k}"] = v
            println "Master '${masterName}' had provisioning configuration item '${k}' change. Updating it."
            masterDefinitionIsChanged = true
        }
    }
    masterDefinitionIsChanged = stateYamlHasChanged(masterDefinition, masterName)

    if (masterDefinitionIsChanged) {
        println "MasterDefinition for master '${masterName}' has changed. Updating it..."
        createOrUpdateBundle(masterDefinition.bundle, masterName)
        setBundleSecurity(masterName, false)

        managedMaster.configuration = currentConfiguration
        managedMaster.save()

        //applyRbacAtMasterRoot(masterName)

        writeStateYaml(masterDefinition, masterName)

        //todo: check if this action can be taken first
        println "Restarting master '${masterName}'."
        managedMaster.restartAction(false)
    } else {
        println "Master Definition for master '${masterName}' same as last run. NOT updating it."
    }
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

private static void setBundleSecurity(String masterName, boolean regenerateBundleToken) {
    sleep(500)
    ExtensionList.lookupSingleton(BundleStorage.class).initialize()
    BundleStorage.AccessControl accessControl = ExtensionList.lookupSingleton(BundleStorage.class).getAccessControl()
    accessControl.updateMasterPath(masterName, masterName)
    if (regenerateBundleToken) {
        accessControl.regenerate(masterName)
    }
}

private static void createOrUpdateBundle(def bundleDefinition, String masterName) {
    String masterBundleDirPath = getMasterBundleDirPath(masterName)
    def masterBundleDirHandle = new File(masterBundleDirPath)

    File jenkinsYamlHandle = new File(masterBundleDirPath + "/jenkins.yaml")
    File pluginsYamlHandle = new File(masterBundleDirPath + "/plugins.yaml")
    File pluginCatalogYamlHandle = new File(masterBundleDirPath + "/plugin-catalog.yaml")
    File bundleYamlHandle = new File(masterBundleDirPath + "/bundle.yaml")

    int bundleVersion = getExistingBundleVersion(bundleYamlHandle) + 1

    if (masterBundleDirHandle.exists()) {
        FileUtils.forceDelete(masterBundleDirHandle)
    }
    FileUtils.forceMkdir(masterBundleDirHandle)

    def yamlMapper = Serialization.yamlMapper()
    def jcascYaml = yamlMapper.writeValueAsString(bundleDefinition.jcasc)?.replace("---", "")?.trim()
    def pluginsYaml = yamlMapper.writeValueAsString([plugins: bundleDefinition.plugins])?.replace("---", "")?.trim()
    def pluginCatalogYaml = yamlMapper.writeValueAsString(bundleDefinition.pluginCatalog)?.replace("---", "")?.trim()
    def bundleYaml = getBundleYamlContents(masterName, bundleVersion)

    if (jcascYaml == "null") { jcascYaml = "" }
    if (pluginsYaml == "null") { pluginsYaml = "" }
    if (pluginCatalogYaml == "null") { pluginCatalogYaml = "" }

    jenkinsYamlHandle.createNewFile()
    jenkinsYamlHandle.text = jcascYaml

    pluginsYamlHandle.createNewFile()
    pluginsYamlHandle.text = pluginsYaml

    pluginCatalogYamlHandle.createNewFile()
    pluginCatalogYamlHandle.text = pluginCatalogYaml

    bundleYamlHandle.createNewFile()
    bundleYamlHandle.text = bundleYaml
}

private static String getMasterBundleDirPath(String masterName) {
    return "/var/jenkins_home/jcasc-bundles-store/${masterName}"
}

private static void createEntryInSecurityFile(String masterName) {
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

private static String getBundleYamlContents(String masterName, int bundleVersion) {
    return """id: '${masterName}'
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
}

private static int getExistingBundleVersion(File bundleYamlFileHandle) {
    if(!bundleYamlFileHandle.exists()) {
        return 0
    }
    def versionLine = bundleYamlFileHandle.readLines().find { it.startsWith("version") }
    String version = versionLine.replace("version:", "").replace(" ", "").replace("'", "").replace('"', '')
    return version as int
}

static boolean stateYamlHasChanged(def stateObject, String masterName) {
    def yamlMapper = Serialization.yamlMapper()

    String stateFileYaml = yamlMapper.writeValueAsString(stateObject)

    // check if state file exists. If not, assume true.
    File stateFileHandle = new File(getMasterBundleDirPath(masterName) + "/state.yaml")
    if(stateFileHandle.exists()) {
        println "State representation exists for master '${masterName}'. Checking parity."
        String existingState = stateFileHandle.text
        String proposedState = stateFileYaml
        if (existingState == proposedState) {
            println "No change in state detected."
            return false
        } else {
            println "Difference in state detected:"
            println StringUtils.difference(existingState, proposedState)
            return true
        }
    } else {
        println "State file for ${masterName} does not exist. Assuming 'changed'."
    }
    return true
}

static void writeStateYaml(def stateObject, String masterName) {
    def yamlMapper = Serialization.yamlMapper()
    File stateFileHandle = new File(getMasterBundleDirPath(masterName) + "/state.yaml")
    def stateFileYaml = yamlMapper.writeValueAsString(stateObject)

    stateFileHandle.createNewFile()
    stateFileHandle.text = stateFileYaml
}