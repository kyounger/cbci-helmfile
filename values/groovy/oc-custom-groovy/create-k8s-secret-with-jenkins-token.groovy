import hudson.model.User
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import jenkins.security.ApiTokenProperty

def userName = 'admin'
def jenkinsTokenName = 'token-for-k8s-secret'
def k8sTokenName = "cbcore-admin-token-secret"
def namespace = "cloudbees"

def user = User.get(userName, false)
def apiTokenProperty = user.getProperty(ApiTokenProperty.class)
def result = apiTokenProperty.tokenStore.generateNewToken(jenkinsTokenName).plainValue
user.save()

def client = new DefaultKubernetesClient()
def createdSecret = client.secrets().inNamespace(namespace).createOrReplaceWithNew()
        .withNewMetadata().withName(k8sTokenName).endMetadata()
        .addToStringData("token", result)
        .done()
