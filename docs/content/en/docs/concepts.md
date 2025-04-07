---
title: Concepts
description: >
  The important concepts required to understand cloud-cleaner
weight: 4
---

### Resource
A resource is a cloud resource in one of the supported cloud providers.
For example, in AWS, a resource can be an EC2 instance, a CloudFormation stack, or an S3 bucket.

### Resource Type
A resource type is a type of resource in one of the supported cloud providers.

### Filter
A filter is a way to include or exclude resources in the cleanup based on their properties.

### Dry-Run
By default, cloud-cleaner will not delete any resources.
This is a safety measure to prevent accidental deletions.
You need to actively pass `--no-dry-run` to delete resources.