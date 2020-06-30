# helmfile for CloudBees CI

## how to use:
1. Ensure you have a k8s cluster created and set as your current context
3. Clone this repo
4. `cp cloudbees-ci-template.yaml cloudbees-ci.yaml`
5. Edit any values you need to, inserting license, defining CasC BundleTemplates, defining masters, etc.
6. Run `helmfile template` to see if all is configured correctly to produce the yaml. Not explicitly necessary, but a nice check before running the next step.
6. Ensure an environment variable named `ENV_ABSTRACTED_PW` is set to some random value and is exported
7. Run `helmfile apply` (or `export ENV_ABSTRACTED_PW=somerandomvalue helmfile apply` if you don't have the env var exported in your shell)
8. Wait for the install to finish and log in.
9. Make an edit to your `cloudbees-ci.yaml` file and rerun `helmfile apply` to see the changes apply.

### Notes

- `cloudbees-ci.yaml` is ignored in git.
- the ENV_ABSTRACTED_PW value is what defines all passwords in ldap. Once an external credential provider is implemented, this will go away.

### Known Issues

* The master provisioning script is run when CJOC starts. However, because it doesn't understand a diff, it just reprovisions _ALL_ the masters each time it starts. This doesn't affect the state of the masters, but it does restart them all at the same time.