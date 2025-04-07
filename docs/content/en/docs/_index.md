---
title: Documentation
linkTitle: Docs
menu: {main: {weight: 20}}
---

{{% pageinfo %}}
This documentation always refers to the latest release of cloud-cleaner.
{{% /pageinfo %}}

cloud-cleaner helps you cleanup your cloud environments (e.g., aws, gcp, azure).
Currently, only aws is supported. Others will follow.

`cloud-cleaner-aws` has the following features:
- Deleting resources in the correct order. Dependencies will be respected.
- Support for deleting resources of multiple AWS accounts in one run.
- Concurrent deletion of resources.
- Supported resource types:
    - CloudFormation stacks
    - more to come