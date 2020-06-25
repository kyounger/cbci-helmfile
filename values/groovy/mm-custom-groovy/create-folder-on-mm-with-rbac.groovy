import com.cloudbees.hudson.plugins.folder.Folder;
import jenkins.model.Jenkins;
import nectar.plugins.rbac.groups.Group;
import nectar.plugins.rbac.groups.GroupContainerLocator;

def appNumbers = System.getenv("APP_NUMBERS")?.split(',');

appNumbers?.each {
    String appNumber = it;

    String folderName = "app-${appNumber}";
    String internalGroupName = "j-GroupDeveloper${appNumber}";
    String externalGroupName = "GroupDeveloper${appNumber}";
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
        group.doAddMember(externalGroupName);
        group.doGrantRole(roleName, 0, Boolean.TRUE);
        container.addGroup(group);
    }
    sleep(500)
}

