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
- **AWS SSO support via profiles** - Use AWS profiles for authentication, perfect for AWS SSO setups.
- Supported resource types:
  - CloudFormation stacks
  - more to come

### Usage
Download the appropriate latest binary from this repository.
```shell
./cloud-cleaner-aws --help # Displays all information needed to run the tool
```

### AWS Authentication

cloud-cleaner-aws supports multiple authentication methods:

#### Using AWS Profiles (Recommended for SSO)
Configure accounts with AWS profiles in your configuration file. This is the recommended approach for AWS SSO:

```yaml
accounts:
  123456789012:
    profile: my-sso-profile
```

You can also combine profiles with role assumption:
```yaml
accounts:
  123456789012:
    profile: my-sso-profile
    assumeRole: OrganizationAccountAccessRole
```

#### Using Role Assumption (Legacy)
Configure accounts to assume a role using default credentials:
```yaml
accounts:
  123456789012:
    assumeRole: admin
```

See `example-sso-config.yaml` for a complete configuration example with AWS SSO profiles.

