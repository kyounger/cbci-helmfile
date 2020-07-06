import com.cloudbees.hudson.plugins.folder.Folder
import jenkins.model.Jenkins
import nectar.plugins.rbac.groups.Group
import nectar.plugins.rbac.groups.GroupContainerLocator

def appIds = System.getenv("APP_IDS")?.split(',');

appIds?.each {
    String appId = it;

    String folderName = "app-${appId}";
    String internalGroupName = "j-Dev${appId}";
    String externalGroupName = "Dev${appId}";
    String roleName = "develop";

    println("folderName: ${folderName}");
    println("internalGroupName: ${internalGroupName}");
    println("externalGroupName: ${externalGroupName}");

    Jenkins jenkins = Jenkins.getInstance();
    def appFolder = jenkins.getItem(folderName);
    if (appFolder == null) {
        println("${folderName} does not exist. Creating it...");
        jenkins.createProject(Folder.class, folderName);
        appFolder = jenkins.getItem(folderName);
    }

    def container = GroupContainerLocator.locate(appFolder);
    if(!container.getGroups().any{it.name=internalGroupName}) {
        Group group = new Group(container, internalGroupName);
        group.setMembers([externalGroupName])
        group.getRoles().each {
            group.doRevokeRole(it)
        }
        group.doGrantRole(roleName, 0, Boolean.TRUE);
        container.addGroup(group);
    }
    sleep(500)
}

