import jenkins.model.Jenkins
import nectar.plugins.rbac.groups.Group
import nectar.plugins.rbac.groups.GroupContainerLocator

def main() {

    def appIds = System.getenv("APP_IDS")?.split(',');

    Jenkins jenkins = Jenkins.getInstance();

    appIds?.each {
        String appId = it;

        String folderName = "app-${appId}";
        String internalGroupName = "j-Dev${appId}";
        String externalGroupName = "Dev${appId}";
        String roleName = "develop";

        applyRbac(folderName, internalGroupName, externalGroupName, jenkins, roleName)
    }

}

private void applyRbac(String folderName, String internalGroupName, String externalGroupName, Jenkins jenkins, String roleName) {
    println("folderName: ${folderName}");
    println("internalGroupName: ${internalGroupName}");
    println("externalGroupName: ${externalGroupName}");
    println("rolename: ${roleName}");

    def appFolder = jenkins.getItem(folderName);
    if (appFolder == null) {
        println("Folder ${folderName} does not exist. Skipping...");
    } else {
        println("Folder ${folderName} exist. Attempting to add Group...");

        def container = GroupContainerLocator.locate(appFolder);

        def groups = container.getGroups()
        groups.each { container.deleteGroup(it)}

        Group group = new Group(container, internalGroupName);
        group.setMembers([externalGroupName])
        group.setRoleAssignments([new Group.RoleAssignment(roleName)])
        try {
            container.setGroups([group])
        // what have I done.
        } catch(ignored) { }

        println("Done with Folder ${folderName}.");
    }
}

main()
