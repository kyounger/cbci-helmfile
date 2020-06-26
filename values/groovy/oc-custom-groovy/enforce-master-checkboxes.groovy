import com.cloudbees.opscenter.server.security.SecurityEnforcer;
SecurityEnforcer.getCurrent().setCrumbIssuer(true)
SecurityEnforcer.getCurrent().setMarkupFormatter(true)
SecurityEnforcer.getCurrent().setMasterKillSwitch(true)
SecurityEnforcer.getCurrent().setRememberMe(true)
