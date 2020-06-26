import hudson.model.Item
import hudson.model.Run
import hudson.model.View
import hudson.scm.SCM
import hudson.security.Permission
import jenkins.model.Jenkins
import nectar.plugins.rbac.groups.Group
import nectar.plugins.rbac.strategy.DefaultRoleMatrixAuthorizationConfig
import nectar.plugins.rbac.strategy.RoleMatrixAuthorizationConfig
import nectar.plugins.rbac.strategy.RoleMatrixAuthorizationPlugin
import nectar.plugins.rbac.strategy.RoleMatrixAuthorizationStrategyImpl

import java.util.logging.Logger

String scriptName = "use-rbac.groovy"
Logger logger = Logger.getLogger(scriptName)

Jenkins jenkins = Jenkins.getInstance()

RoleMatrixAuthorizationPlugin matrixAuthorizationPlugin = RoleMatrixAuthorizationPlugin.getInstance()
RoleMatrixAuthorizationConfig config = new DefaultRoleMatrixAuthorizationConfig();
RoleMatrixAuthorizationStrategyImpl roleMatrixAuthorizationStrategy = new RoleMatrixAuthorizationStrategyImpl()
jenkins.setAuthorizationStrategy(roleMatrixAuthorizationStrategy)

String ROLE_ADMINISTER = "administer";
String ROLE_DEVELOP = "develop";
String ROLE_BROWSE = "browse";

Map<String, Set<String>> roles = new HashMap<String, Set<String>>();

//Give admins all permissions
for (Permission p : Permission.getAll()) {
    roles.put(p.getId(), new HashSet<String>(Collections.singleton(ROLE_ADMINISTER)));
}

//Give developers permissions they need
roles.get(Item.PERMISSIONS.getId()).add(ROLE_DEVELOP)
roles.get(SCM.PERMISSIONS.getId()).add(ROLE_DEVELOP)
roles.get(Run.PERMISSIONS.getId()).add(ROLE_DEVELOP)
roles.get(View.PERMISSIONS.getId()).add(ROLE_DEVELOP)

//Give browsers permissions they need
roles.get(Jenkins.READ.getId()).add(ROLE_BROWSE);
roles.get(Item.DISCOVER.getId()).add(ROLE_BROWSE);
roles.get(Item.READ.getId()).add(ROLE_BROWSE);

List<Group> rootGroups = new ArrayList<Group>();

def administratorsGroup = new Group("Administrators");
administratorsGroup.setMembers(["admin", "kenny"]);
administratorsGroup.setRoleAssignments(Collections.singletonList(new Group.RoleAssignment(ROLE_ADMINISTER)));
rootGroups.add(administratorsGroup);

//def browsersGroup = new Group("Browsers");
//browsersGroup.setMembers(Collections.singletonList("authenticated"));
//browsersGroup.setRoleAssignments(Collections.singletonList(new Group.RoleAssignment(ROLE_BROWSE)));
//rootGroups.add(browsersGroup);

config.setRolesByPermissionIdMap(roles);
config.setFilterableRoles(new HashSet<String>(Arrays.asList(ROLE_BROWSE, ROLE_DEVELOP)));
config.setGroups(rootGroups);

matrixAuthorizationPlugin.configuration = config
matrixAuthorizationPlugin.save()
logger.info("RBAC Roles and Groups defined")
