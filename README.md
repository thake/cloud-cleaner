# cloud-cleaner

This repository contains tools to clean cloud environments (e.g. AWS accounts).
For now only AWS is supported.

Further documentation: [cloud-cleaner website](https://cloud-cleaner.thorsten-hake.com/)

## cloud-cleaner-aws

cloud-cleaner-aws deletes AWS resources in one or more AWS accounts.
It implements the following features:
- Deleting resources in the correct order. Dependencies will be respected.
- Support for deleting resources of multiple AWS accounts in one run.
- Concurrent deletion of resources.
- Supported resource types:
  - CloudFormation stacks
  - more to come

### Usage
Download the appropriate latest binary from this repository.
```shell
./cloud-cleaner-aws --help # Displays all information needed to run the tool
```

