# CloudBees CI, Entirely As Code — An Opinionated Approach

Many of us in the Services organization at CloudBees have worked closely with customers to provide guidance on designing, implementing, and operating their installation of CloudBees CI (CBCI, formerly known as CloudBees Core). While no two customers are alike, there are some approaches that seem to work better than others. This Opinionated Approach is based on several goals and guiding principles. If those are acceptable, this should help automate the operation.

This is part specification, and part documentation (and justification) of a reference implementation. If the goal is "everything as code" as the title claims, we also better provide some code!

The main vision is to showcase what's possible. Getting to "everything as code" here does mean there are some hard stops. Not everything in Jenkins or CloudBees CI is ready to be declared somewhere in a manifest file in git. Many parts of Jenkins still want their source of truth to be a filesystem. However, with the recent GA release of the *CloudBees CasC for Masters* feature, we now have a supported approach for configuring masters. Most of the components for this vision are now available. This is an attempt to put them together. 

And fill some gaps.

### CloudBees CI Modern

First thing to note: this is for "CloudBees CI Modern", the Kubernetes-based variant, and not "CloudBees CI Traditional". Kubernetes makes much of this possible, as it helps abstract many of the infrastructure concerns (though, they are concerns, indeed). Most of these opinions and approaches could be reworked into a Traditional install — but providing a reference implementation for it is less attractive, as the diversity in the underlying infrastructure, configuration management and other disparate tooling, and operation of it all, is much, much greater. This is part of the appeal of Kubernetes.

### Abbreviations

CJOC, OC = Operations Center
MM = Managed Master
TM = Team Master
JCasC = OSS Jenkins `configuration-as-code` plugin/solution.
CasC = CloudBees CasC for Masters (CloudBees-specific solution built on top of JCasC)

## Goals, Guiding Principles, Decision-making

- A single file to declare an entire installation.
- Updating this single file is the mechanism to affect the state of the cluster.
- A recognition that CloudBees CI is never the only piece of software installed into a single cluster.
- No restarts of CJOC or a master to ensure an install is "complete". Only exception here is when Jenkins just naturally requires a restart (e.g. after a plugin installation).
- No manual interventions during install/upgrade.
- No deriving new docker images.
- No forks of an upstream helm chart.
- Uses only Jenkins/CloudBees CI/Kubernetes/helm primitives and templating via Go Templates in helm and helmfile. We agument and fill gaps where we have to (pre-pulling extra plugins on CJOC, JCasC on CJOC, Groovy scripts where JCasC doesn't work, etc.)
- Favor self-configuration. As an example, we show preference to mount a groovy script to a master so it boots up and configures RBAC itself, versus booting the master and subsequently calling its REST API.
- No admin access to OC or Masters for anyone outside the team that operations this installation. All RBAC is programmatically configured and all access is assigned from the level of a folder inside a master.
- No one should ever need to go into the CJOC UI (and possibly never a Master's UI).
- Self-servicing a master should be simple and secure.
- Make decisions that maintain the supportability of the product, while enabling functionality that might not be present out of the box.
- Scaling, and testing CloudBees CI at scale, is a first-class concern.
- Begin treating masters like cattle.

## Gaps

What are the gaps we're filling? How difficult would it be to reproduce these unsupported automations manually given the need for support? 

Note that many of these are easy to reproduce individually. Taken together, it might require an engineer a good amount of time to effectively rebuild an environment manually. Pehaps that illustrates the benefits, though.

### Operations Center Programmatic Install

Currently you cannot install CloudBees CI without human interaction. There are serveral components that require custom automation:

#### Skip Wizard

First things first, we skip the installation wizard with a cli flag (`-Djenkins.install.runSetupWizard=false`), as it is a manual interface.

#### License

We apply a license with a groovy init script. There are other approaches to this, but in this case the groovy script is super simple. While groovy is not supported, this relatively innocuous script is probably of little harm.

#### Tier1/Tier2 plugin installation

We use a special flag (`-Dcb.IMProp.fsProfiles`) and mount a specific file to tell the OperationsCenter to install a certain set of plugins from the envelope when booted.

#### Tier3 plugin installation

Because the OC allows you to install tier 3 plugins from its Update Center after boot, having these installed programmatically (again, without restart) is required. We achieve this by detecting the version of Operations Center ahead of time (via the helm chart app version), and using that version, download the plugin directly from that UC and mount it in a PV that will be mounted to the container. This process is contained in the `oc-extra-plugins` chart.

#### Jenkins Configuration

If you want to automate the setup of various System Configuration of the underlying Jenkins (and its plugins) for the Operations Center, you have two options: 1) Groovy scripts, or 2) JCasC. Both are unsupported.

The general direction of the product is moving toward JCasC support in the OC. While it's not yet supported, [it is intended to be](https://www.cloudbees.com/blog/casc-for-controllers). Therefore my decision here was to pick using JCasC where it works now (e.g. Auth Realm setup), and using Groovy scripts where it isn't (e.g. enabling RBAC and initial configuration thereof). Hopefully this parlays easily into JCasC support when it lands with fewer changes needed.

### Managed Master Provisioning

Typically we provision each individual MM via the Operations Center Managed Master Provisioning UI. This requires a human to click buttons and type in each specific input the desired configuration of a master, then boot it.

#### Programmatic Creation/Update of Managed Masters

We solve this by implementing a "shim" (a groovy script) that interfaces with the underlying Operation Center master provisioning plugin. It attempts to reproduce the same method calls the UI does to create+start/update+restart a MM.

The goal is to define a master as code, then define _ALL_ masters as code, then use that definition of state to allow some other process to ensure that state is reconciled in the runtime. If this sounds familiar to you, you might understand the Operator/Controller pattern in Kubernetes. This is precisely the direction taken here. In the interests of saving time and effort as a PoC, I have used a very simple operator framework to trigger the shim script when the configuration of the masters changes:

https://github.com/flant/shell-operator

https://github.com/kyounger/shell-operator-derivatives

### CasC Automation (Master Templating)

The new *CloudBees CasC for Masters* feature is what ultimately enables this approach. The CasC bundle needs to be defined on the OC before the MM is instantiated. Additionally, for Tier 3 plugins, you are required to manage which versions from the UpdateCenter are going to be installed, as well as any transitive dependencies for that plugin and their versions.

Handling these setups proves the need for this automation; it's easily fat-fingered. 

TODO: elaborate more about how the templates work.

Tier 3 plugins require a different approach. The entire MM Update Center at the current version is downloaded. This allows us to then use that payload as an envrionmental values datasource in helmfile, which allows us to determine the version to specify in `plugin-catalog.yaml` programmatically. This is literally the same thing that Jenkins does when you click through the UI to install a plugin. Sadly, solving the transitive dependencies problem is not yet part of this solution.

#### Folder/Item Definitions

Any item in a MM will need to be defined as code. Currently we use job-dsl to create folder structure and GH org folders.

Another item to note is that "AppIDs" equate to a specific application identifier. This seems to be common in large, well-controlled organizations. We define that as a first class citizen of a solution. If you define an AppID to reside on a master, then it will get a folder created and associated RBAC applied and a Github Org folder inserted into it, linked back to the expected location for that AppID's repos to be defined.

#### Automated RBAC applied to Folders

Currently we use a groovy script to apply RBAC to folders that are expected to be there.

## What exactly is "as code"?

### All of it

The intention is that _everything_ is as code. There should be no need to manually configure anything via the UI of CJOC, nor a master.

Ultimately, the flow of creating _everthing_ will be:

```shell
git clone ...
vim cloudbees-ci.yaml #where you specify cluster-specific details. vim not required ;)
helmfile sync
```

And once installed, repeated use of:

```shell
vim cloudbees-ci.yaml
helmfile apply
```

to adjust cluster state. This last process is also very easily wrapped into a CI/CD pipeline.

### Components

- Infrastructure

  * GCP terraform module for CloudBees CI provides an example of how to provision a Kubernetes cluster. Creates all the necessary GCP resources, the GKE cluster and its NodePools. 
  * GCP/GKE are not required. Any Kubernetes cluster 1.15 or later should [theoretically] work with this approach. Currently only tested in GCP/GKE.
  * TODO! This component is not published yet, but can be shared if needed.

- Applications

  * All applications (and supplemental resources) are packaged as helm charts.
  * `helmfile` declaratively manages all the helm chart installations

  - Helm Charts
    - CloudBees CI (`cloudbees-core`) helm chart
      - CJOC configuration and plugins
      - All components of CJOC are declaratively managed as code.
      - Masters are defined as code.
      - Masters can be templated.
      - Item definitions
    - cert-manager
      - Provides TLS certificates via Let's Encrypt, or any other ACME-compliant Certificate Authority
    - nginx-ingress
      - The only supported ingress controller. Works well, good support and documentation.
    - prometheus
    - grafana
    - openldap
      - Probably the most common authentication realm used with CloudBees CI. Also open source and easily configured. A good pick for a reference implementation.
    - helper charts via the incubator/raw chart — these will be explained in detail later.
      - oc-extra-plugins
      - cm-cluster-issuer
      - master-definitions

## What is helmfile? Why is this needed?

Helm has become the defacto standard for packaging applications deployed into Kubernetes. Under the hood, the helm chart is basically a collection of yaml templates. And the helm (v3) client is basically a client-side yaml templating engine, coupled with a deployment mechanism. Yaml templating is important because it allows reuse of top-level values as a sort of "public API" for the chart.     The chart can then be reused, the underlying implementation refactored, or backward/foward compatible changes introduced.

### So many charts

This is pretty fantastic until you start needing to:

1. Define all aspects of the helm upgrade as code (including chart version, repo locations, namespaces, etc.).
2. Operate across multiple environments the helm installation of more than a few charts (i.e. hello makefiles and shell script loops). 
3. Run these upgrades through a CI/CD pipeline.
4. Include certain charts (a tool-specific UI) in one environment (test) and not in another (prod).
5. Coordinate value changes across multiple charts. In particular, if there are calculations needed from one value in one chart to produce another value in another chart.
6. You would like to make a particular template for the _values_ of a helm release.
7. Define a dependency graph for your charts.
8. Augment a chart with custom resources.

There are a few tools/approaches out there that can help solve some of these issues. Let's look at some of the choices here to understand why I picked helmfile.

### Options to address the problem

#### Shell scripts and makefiles

No, thanks.

#### Aggregate helm charts

This works fairly well, especially for very simple needs — the CBCI helm chart even aggregates two other charts (nginx-ingress & cloudbees-sidecar-injector) — and provides some of the needed functionality for coordination across multiple charts.

Downsides are that a `helm list` only shows one release, and that release can only go into a single namespace. Each time you do a deployment, you update all the charts. Each time you do a rollback you have to rollback every chart. You are usually still forced into using some kind of external tool for CI/CD pipelines to manage the `helm upgrade` flags as code.

#### Helmsman

For me, part of the attractiveness of helmfile was that it was basically a "superset" of helm. All the same yaml templating works as you'd expect. Helmsman did not have templating and used toml as file format. We are already adding another tool, I would prefer not to add another data format. To that end, I didn't use it, so it might be great!

#### Helm Operator / Flux

I felt these were a bit too heavy for what we're trying to accomplish. Additionally, the Helm Operator has to be installed... with helm. This sort of defeats the purpose here, in my mind. However, we wholeheartedly recommend a GitOps approach.

#### Helmfile

As I mentioned above, helmfile could be well-thought-of as a "superset" of helm. It's largely an additional Go Templating engine layer to produce a list of helm releases + values files via that templating engine. If you're comfortable with developing helm charts, helmfile is a very short learning curve.

My experience with this tool is that it seems to check all the boxes. My only complaint is that it is definitely "in active development". I wouldn't recommend you upgrade it willy-nilly and definitely click on the "Follow" button on GitHub to be sure you are keeping abreast of changes.

Helmfile is a good approach to operating helm installs via the `apply` command in a CD pipeline.

##### Terraform helm/helmfile providers

What about the helm or helmfile providers that are available for terraform (or using any other IaC tool)?

That is a reasonable approach. The perspective here is that the applications are managed separately from the infrastructure. But sometimes that overlaps. Nothing wrong with having terraform call helmfile if that works for you. There is also a terraform-helmfile-provider that might be worth looking into if you have interest in really hooking these tools together. 

A single command of `helmfile apply` (possibly with the added `-e` flag, to specify an environment) is the goal. This enables effective long term operation of the cluster and forces this to be the only imperative interface. The terraform module outputs a yaml file that can be fed as an external environment values file to helmfile. However, we don't expect much of the state of the cluster to change to the degree that hooking these tools together would provide long-term continous benefit.

### Conclusion

Helmfile was a natural fit and works well for this use case. These other tools are great and have their place, and might do a better job given a different set of requirements. If you like them, use them! This is an opinionated approach, so sticking with that theme: helmfile it is.

### Gaps

Helmfile also allows us to fill some gaps without forking the `cloudbees-core` helm chart (or others). E.g. defining masters and being able to install resources specifically for them, adding the extra plugins to the OC, etc.

## Caveat Emptor

### Supported?

Not all of this is supported by CloudBees. Part of the intention behind this effort, again, is to showcase what is possible, not provide a 100%-supported solution based on 100%-supported components. That is not currently possible. Examples of what is not supported would be any of the Tier 3 plugins (e.g. `job-dsl`, `prometheus`), any of the groovy scripts, putting the `configuration-as-code` plugin on CJOC, etc. So, user beware.

### Be Ready

If you do have a bug that you think is attributable to the underlying product, and not a filled gap, then the best situation to get support on that is to replicate the bug in a system that doesn't use the gap fillers. This means installing with no groovy auto-configuration, no jcasc on OC, provisioning masters manually, creating items manually, configuring RBAC manually, etc.

You can decide if the benefits of using this fully-automated system outweigh the costs of potential out-of-support pitfalls.

## Getting started

### Prerequisites

1. Ensure you have a GKE cluster created and set as your current context.
2. Currently the installer expects a static ip for the nginx-ingress-controller LoadBalancer. This needs to be specified in the initial values.
3. DNS host entry that points to the static IP.
4. These cli tools are used and need to be installed: `helm` (v3), `helmfile`,  `jq`, `yq`, and `wget` (used for fetching tier3 plugins)
5. The [ `helm-diff` plugin](https://github.com/databus23/helm-diff#install) must be installed.

### Create

1. Clone this repo.
2. `cp cloudbees-ci-template.yaml cloudbees-ci.yaml`
3. Edit any values you need to, inserting license, defining master templates, masters, etc.
4. Run `helmfile template` to see if all is configured correctly. Not explicitly necessary, but a nice check before running the next step.
5. Ensure an environment variable named `ENV_ABSTRACTED_PW` is set to some random secure value and is exported. 

>  For demonstration purposes this is how we set all passwords in a reasonably secure manner. In the TODO list is implementing external credentials — which is clearly needed for any sort of proper implementation.

6. Run `helmfile sync`
> (or `export ENV_ABSTRACTED_PW=somerandomvalue helmfile sync` if you don't have/want the env var exported in your shell). We have to use `sync` instead of `apply` on the first run in case there are any CRDs that need to be installed into the cluster — these will fail on a `apply/diff`, because it can't validate the generated resources against their required definitions.
7. Wait for the install to finish and log in.

### Update

1. Make an edit to your `cloudbees-ci.yaml` file.
2. Run `helmfile apply` to see the changes apply.
3. `helmfile apply` is used after the initial install, unless you are adding CRDs _AND_ those CRDs are used by a chart in the same operation. Our first install is exactly this, because of the cert-manager CRDs that need to exist.

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

* The master provisioning script is run by a kubernetes job after CJOC starts. There is need to determine how to run this. Implemeting a simple controller would be appropriate based on changes to master-definitions configmap. Long-term, we hope that masters are defined by a CRD or another helm chart.
* CasC seems to require a definition of a plugin-catalog in the bundle. You can't leave the `plugin-catalog.yaml` file empty, though. Workaround for now is to make sure you are specifying a plugin catalog for all CascBundleTemplates, even if you aren' adding that plugin in the `plugins.yaml` file.
* Inheritance works for `provisioning`, `plugins`, and any nodes within `jcasc` that are NOT lists. The merging function used considers a list the leaf of the merge tree and will replace it entirely.

------------

## Breaking down how it works

TODO: need to work through how to explain each part of the solution

* Evolutionary approach to how I got this to where it is.
* Start small and show how to build it up.
* Maybe point out specific components that are "hard" as-code and explain that gap and the approach to fill it.
* How to use various command as tooling to help things (hf diff, hf --debug to show values files)

Some fleshed out sections that need to be inserted above where it makes sense:

### Why all the focus on AppIDs?

A common paradigm used by many organizations is to control applications/components that can be delivered into production by requiring a process to request an ID from a governance system. This ID controls the entire lifecycle of the application, as well as authorization and authentication mechanisms typically via an Identity Provider. The idea is that a new developer or team lead can be onboarded to an application very simply, and the tools (e.g. CloudBees CI, etc.) only accept valid IDs to be included in the CI/CD pipelines. No application can read production unless it goes through this governance process.

### Why do we break up AppIDs across masters? Why wouldn't we just put them all onto a single master?

#### Security

Depending on your level of trust and threat modeling, you may be comfortable running fewer masters. Part of the consideration here is that CloudBees CI Modern runs on Kubernetes and securing workloads in Kubernetes requires us to implement certain design patterns. For example, we put CJOC, each master, and each master's agents in their own respective Namespaces and give them their own Service Accounts (note, this is still TODO). This might seem like overkill to some (and for some it might be), but to ensure masters (and their agents) are isolated from each other, this is required. Additionally, a master's agents typically have a lower trust threshold and therefore are run in their own namespace with their own Service Account.

#### Resource Contstraints

Masters can be resource constrained. It's not common as long as we're following best practices with regard to pipeline development (i.e. zero or few plugins that affect the pipeline, declarative syntax, etc.), but the potential is there for a team to build a pipeline, and run enough of them concurrently, that the resources consumed by the agents could create a noisy-neighbor situation. Putting each master's agents into their own namespace, and resource constraining that namespace can be useful. Since we're automating all of this, it's also pretty trivial to add in as part of the design.

#### Single point of failure

Masters are a single point of failure.

#### System/Plugin Configuration Conflicts ####

If you've run a large Jenkins instance with a lot of disparate usage, you have experienced this: one team needs to upgrade a plugin, or affect some system config, but doing so will break another team's usage of that master. If a single team does need to use very specific plugins (we advise against this, but maybe the fight just isn't worth it 😉), then splitting them off into their own master can be a way to shield other teams from this. Additionally, it is not uncommon for a team to see a plugin installed and just start using it.

### Why all this code?

- Security
- Stability
- Gitops / Cloud-native mentality
- DR/BR concerns
- Controls / Audit
- Automate everything, so none of this is in people's heads
- Replicate an environment easily to a lower environment for testing.
- Dev/Test/**/Prod environment parity is achievable and provable. 

## TODOs

### In general order of priority

- [ ] overriding cpus seems to break

- [x] combine master-definitions and master-provisioner charts

- [ ] make master-provisioning-shim output more obvious when something changes?

  - Currently running via shell-operator with has it's own issues with logging. Not a priority at the moment.

- [x] Decide on _how_ to run master-provisioner-shim.groovy. This needs to be _triggered_ on an install (or restart of CJOC?), but shouldn't be solely tied to a CJOC restart. Until piecemeal updates works, run this manually, otherwise every master is restarted and re-bundled when CJOC is restarted 😱.

  - [x] Run as init groovy script on cjoc -- this breaks so many thing.

  - [x] Run initially as a k8s job to demonstrate initial install, and capability to run this script from in the cluster, but outside CJOC

  - Approaches:

    - [x] Deploy a simple controller to check when the `master-definition` config map changes and just ping OC with the shim
      - https://github.com/flant/shell-operator
      - https://medium.com/flant-com/kubernetes-shell-operator-76c596b42f23
      - https://github.com/flant/addon-operator

    * Ops-mm with a something that can detect when the configmap changes? On a cron schedule (ugh)?
    * CRAZY: demonstrate how to use a set of CRDs (`MasterTemplate`s, `MasterDefinition`s, etc.)

- [ ] Continue to flesh out documentation

  - [ ] document each _gap_ and how we're filling it
  - [ ] document each unsupported approach?
  - [ ] document how to "upgrade master images"

- [ ] AppID fully implemented

  - [x] Creating the users/groups in ldap based on appIds
  - [x] Switch to using job-dsl folder creation entirely and only have the groovy script apply RBAC groups?
  - [ ] Update Github tf module
    - [ ] add repo `Jenkinsfile`s
  - [x] Add job-dsl `-script` "append"
  - [ ] Automating Shared library setup
    - [ ] global shared library
    - [ ] AppID specific library

- [ ] Multi-namespace

  - namespace for just OC
  - namespace per master
  - namespace per master's agents
  - Ensure NetworkPolicy is adjusted here

- [ ] Detect k8s flavor and adjust cloudbees-core install to use that Platform

- [ ] Create a container with all the required tooling.

  - [ ] Example of how to use a k8s Job to do the deployment?

- [ ] Prometheus + Grafana

  - [x] Add helm charts and config
  - [x] Monitor each master
  - [ ] Prometheus plugin 
    - [ ] on CJOC
      * 🔴 BLOCKED. This BREAKS because `prometheus` plugin not in OC UC!
    - [x] on masters
    - [x] automatic configuration
  - [ ] push `https.enabled` down into prometheus/grafana charts
  - [ ] Authenticate the endpoint
    - [x] jcasc configuration for this
    - [ ] automatically add service account token and store in proper place for prometheus to use
  - [ ] Explore queries
  - [ ] Fix ingress issues
  - [ ] Get granfana configured properly
    * https://grafana.com/grafana/dashboards/12571

- [ ] Vault / external credentials

- [ ] TF module for all this

  - [ ] refactor and publish

- [ ] Local Registry (nexus/artifactory would be more universal, but GCR is right there...) and enforce image pulls from it

- [ ] Networkpolicy

- [ ] Podsecuritypolicy

  - [ ] enforce PSP: https://www.terraform.io/docs/providers/google/r/container_cluster.html#pod_security_policy_config

  - [ ] no root, no privileged

  - [ ] Kaniko + gvisor nodes 

    * allows for root user access, if required
    * need some kind of convention to guarantee that we get a gvisor node when requested
    * create a gvisor nodepool with tf in gke

- [ ] GKE cluster in a VPC

- [ ] Item creation

  - [x] job-dsl for now
  - [x] Current plan is a single github-branch-source org created per AppID
    - Groovy is possible, too, since we're already creating the AppID folders/rbac on the masters

- [ ] Do we recommend approaches to templating pipelines?

  - My recommended approach for templating: https://www.jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/

- [ ] External agents (e.g. standard windows agents) for a master, how to let a team define this?

- [ ] Consider backup/restore of PVs

  * velero

    * with underlying SC snapshotting
    * in-process backups
    * restore process for this entire system based on a backed-up state
  * CB B/R plugin

- [ ] Pipelinepolicy

- [ ] Autoscaling

  - [ ] MM hibernation

- [ ] Set up CI/CD for this

  * PR -> some existing pipeline -> git checkout of repo with change -> run tf apply to create a test env -> hf apply -> manual validation of env

  - Process0 problem
  - Replicating the entire cluster into another environment (i.e. for testing purposes, DR)

- [ ] Create some "weird" bundles

  - A LOT of plugins. See if you can get 300-500 plugins installed this way

- [ ] build logs -- need to ship these off-master

  * github reporting plugin

- [ ] scaling section in docs

- [ ] Find a way to walk the dependency tree to not have to specify all transitive dependencies of tier3 plugins

- [ ] idea: create process that defines a "default" master's /core-casc-export/ data and quickly diffs and shows the details only relevant to a master that looks slightly different

- [ ] figure out other metrics/monitoring aside from prometheus?

### What else would we want that this doesn't cover?