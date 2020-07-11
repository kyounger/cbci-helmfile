# helmfile for CloudBees CI

## Getting started
1. Ensure you have a k8s cluster created and set as your current context
3. Clone this repo
4. `cp cloudbees-ci-template.yaml cloudbees-ci.yaml`
5. Edit any values you need to, inserting license, defining master templates, masters, etc.
6. Run `helmfile template` to see if all is configured correctly to produce the yaml. Not explicitly necessary, but a nice check before running the next step.
6. Ensure an environment variable named `ENV_ABSTRACTED_PW` is set to some random value and is exported
7. Run `helmfile sync` (or `export ENV_ABSTRACTED_PW=somerandomvalue helmfile sync` if you don't have the env var exported in your shell). We have to use `sync` instead of `apply` on the first run in case there are any CRDs that need to be installed into the cluster — these will fail on a `apply/diff`, because it can't validate the generated resources against their required definitions.
8. Wait for the install to finish and log in.
9. Make an edit to your `cloudbees-ci.yaml` file and rerun `helmfile apply` to see the changes apply. 

### Notes

- `cloudbees-ci.yaml` is ignored in git.
- the ENV_ABSTRACTED_PW value is what defines all passwords in ldap and a few other places. Once an external credential provider is implemented, this will go away.

### Reference

* Inheritance works, but there is are only three layers. 
  1. a `default` masterTemplate that all other templates inherit from, and
  2. a masterTemplate that is defined that can specify deltas on top of the default
  3. specific master definitions can specify their own deltas
* Each master must specify a masterTemplate, even if it is the default.
* The `manyMasters` section allows for massive scaling of masters. Not entirely sure if this is effective for production use, but the intention of including this here is for testing scaling.
* You only need to specify plugins in the list. Each is specified as the key in a map with a value `{version: auto}`. This is required currently, and cannot be changed.
* PluginCatalogs are derived based on the CloudBees UpdateCenter for ManagedMasters (envelope-core-mm) for the current version of the chart/app. If a plugin is not specified in the envelope, it is added to that master's PluginCatalog as the version specified in the UC.
* Transitive dependencies are _STILL_ not calculated. (You try walking that tree in go templates!)


These are properties you can set on the provisioning section of a master template/definition. You have to be careful with some of these.
```
allowExternalAgents: false, //boolean
clusterEndpointId: "default", //String
cpus: 1.0, //Double
disk: //Integer
envVars //String
domain: "readYaml-custom-domain-1", //String
fsGroup: "1000", //String
image: "custom-image-name", //String -- set this up in Operations Center Docker Image configuration
javaOptions: "${KubernetesMasterProvisioning.JAVA_OPTIONS} -Dadditional.option", //String
jenkinsOptions:"", //String
kubernetesInternalDomain: "cluster.local", //String
livenessInitialDelaySeconds: 300, //Integer
livenessPeriodSeconds: 10, //Integer
livenessTimeoutSeconds: 10, //Integer
memory: //Integer
namespace: null, //String
nodeSelectors: null, //String
ratio: 0.7, //Double
storageClassName: null, //String
systemProperties:"", //String
terminationGracePeriodSeconds: 1200, //Integer
```
(Technically there is a yaml definition, but that is not accessible since we use it. Merging that might work?)

### Known Issues

* The master provisioning script is run when by a kubernetes job after CJOC starts. There is need to determine how to run this. Implemeting a simple controller would be appropriate based on changes to master-definitions configmap. Long-term, we hope that masters are defined by a CRD or another helm chart.
* CasC seems to require a definition of a plugin-catalog in the bundle. You can't leave the `plugin-catalog.yaml` file empty, though. Workaround for now is to make sure you are specifying a plugin catalog for all CascBundleTemplates, even if you aren' adding that plugin in the `plugins.yaml` file.
* Inheritance works for `provisioning`, `plugins`, and any nodes within `jcasc` that are NOT lists. The merging function used considers a list the leaf of the merge tree and will replace it entirely.

