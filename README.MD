# helmfile for CloudBees CI

## how to use:
1. Ensure you have a k8s cluster created and set as your current context
3. Clone this repo
4. `cp defaults-template.yaml defaults.yaml`
5. Edit any values you need to, inserting license, etc.
6. Run `helmfile template` to see if all is configured correctly to produce the yaml. Not explicitly necessary, but a nice check before running the next step.
7. Run `helmfile apply`.
8. Wait for the install to finish and log in.
9. Make an edit to your `defaults.yaml` file and rerun `helmfile apply` to see the changes apply.

Note that `defaults.yaml` is ignored in git.
