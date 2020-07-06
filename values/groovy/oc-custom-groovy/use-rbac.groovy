import hudson.model.Item
import hudson.model.Run
import hudson.model.View
import hudson.scm.SCM
import hudson.security.Permission
import hudson.security.PermissionGroup
import jenkins.model.Jenkins
import nectar.plugins.rbac.groups.Group
import nectar.plugins.rbac.groups.GroupContainer
import nectar.plugins.rbac.groups.GroupContainerLocator
import nectar.plugins.rbac.strategy.DefaultRoleMatrixAuthorizationConfig
import nectar.plugins.rbac.strategy.RoleMatrixAuthorizationConfig
import nectar.plugins.rbac.strategy.RoleMatrixAuthorizationPlugin
import nectar.plugins.rbac.strategy.RoleMatrixAuthorizationStrategyImpl

import java.util.logging.Logger

String scriptName = "use-rbac.groovy"
Logger logger = Logger.getLogger(scriptName)

Jenkins jenkins = Jenkins.getInstance()

if(jenkins.getAuthorizationStrategy().getClass().toString() == RoleMatrixAuthorizationStrategyImpl) {
    logger.info("Auth strategy already set to use RBAC.")
} else {
    logger.info("Setting auth strategy to use RBAC.")
    RoleMatrixAuthorizationStrategyImpl roleMatrixAuthorizationStrategy = new RoleMatrixAuthorizationStrategyImpl()
    jenkins.setAuthorizationStrategy(roleMatrixAuthorizationStrategy)
}

// Define roles
String ROLE_ADMINISTER = "administer";
String ROLE_DEVELOP = "develop";
String ROLE_BROWSE = "browse";
String BUILTIN_ROLE_AUTHENTICATED = "authenticated";
String BUILTIN_ROLE_ANONYMOUS = "anonymous";

Map<String, Set<String>> roles = new HashMap<String, Set<String>>();

// Create roles map, and automatically give admins all permissions
for (Permission p : Permission.getAll()) {
    roles.put(p.getId(), new HashSet<String>(Collections.singleton(ROLE_ADMINISTER)));
}

// Develop role
roles.get(Jenkins.READ.getId()).add(ROLE_DEVELOP);
for (PermissionGroup pg : [Item.PERMISSIONS, SCM.PERMISSIONS, Run.PERMISSIONS, View.PERMISSIONS]) {
    for (Permission p : pg.getPermissions()) {
        roles.get(p.getId()).add(ROLE_DEVELOP);
    }
}
// Browse role
roles.get(Jenkins.READ.getId()).add(ROLE_BROWSE);
roles.get(Item.DISCOVER.getId()).add(ROLE_BROWSE);
roles.get(Item.READ.getId()).add(ROLE_BROWSE);

// Authenticated to get Overall/Read
roles.get(Jenkins.READ.getId()).add(BUILTIN_ROLE_AUTHENTICATED);

// Set role config
RoleMatrixAuthorizationPlugin matrixAuthorizationPlugin = RoleMatrixAuthorizationPlugin.getInstance()
RoleMatrixAuthorizationConfig config = new DefaultRoleMatrixAuthorizationConfig();

config.setRolesByPermissionIdMap(roles);
config.setFilterableRoles(new HashSet<String>(Arrays.asList(ROLE_BROWSE, ROLE_DEVELOP)));
matrixAuthorizationPlugin.configuration = config
matrixAuthorizationPlugin.save()
logger.info("RBAC Roles defined")

// Add external Admin group and map to role
String internalGroupName = "j-Administrators"
String externalGroupName = "Administrators"
GroupContainer container = GroupContainerLocator.locate(jenkins)
Group group = new Group(container, internalGroupName)
group.setMembers([externalGroupName])
group.setRoleAssignments([new Group.RoleAssignment(ROLE_ADMINISTER)])
container.setGroups([group])
logger.info("RBAC Groups defined")

logger.info("Finish ${scriptName}")
