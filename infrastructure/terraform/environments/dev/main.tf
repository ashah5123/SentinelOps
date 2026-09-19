# Low-cost development environment: single NAT gateway, single-AZ RDS, one Redis node, a
# 2-broker MSK cluster on the smallest instance class, and a small EKS node group. Suitable for
# exercising the full production-shaped architecture at minimal cost, never for real traffic.
#
# This configuration was written and reviewed but NOT applied — `terraform apply` was never run
# against a real AWS account for this phase (no cloud credentials or explicit authorization were
# provided). See docs/development/production-deployment.md for the exact review/apply procedure.

terraform {
  required_version = ">= 1.7"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # Example remote-state backend — uncomment and fill in with a real, pre-created bucket/table
  # before the first real `terraform init`. Left commented so this file stays plan-only safe by
  # default (no accidental local state clobbering a shared backend).
  # backend "s3" {
  #   bucket         = "sentinelops-terraform-state-dev"
  #   key            = "dev/terraform.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "sentinelops-terraform-locks-dev"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = local.tags
  }
}

locals {
  name = "sentinelops-dev"
  tags = {
    Project     = "sentinelops"
    Environment = "dev"
    ManagedBy   = "terraform"
    Owner       = var.owner_tag
  }
}

module "networking" {
  source             = "../../modules/networking"
  name               = local.name
  vpc_cidr           = "10.30.0.0/16"
  azs                = var.azs
  single_nat_gateway = true
  tags               = local.tags
}

module "eks" {
  source              = "../../modules/eks"
  name                = local.name
  vpc_id              = module.networking.vpc_id
  private_subnet_ids  = module.networking.private_subnet_ids
  node_instance_types = ["t3.medium"]
  node_min_size       = 1
  node_max_size       = 3
  node_desired_size   = 1
  tags                = local.tags
}

module "postgres" {
  source                     = "../../modules/rds-postgres"
  name                       = local.name
  vpc_id                     = module.networking.vpc_id
  private_subnet_ids         = module.networking.private_subnet_ids
  allowed_security_group_ids = [] # populate with the EKS node security group once known post-apply
  instance_class             = "db.t4g.micro"
  allocated_storage_gb       = 20
  multi_az                   = false
  backup_retention_days      = 3
  deletion_protection        = false
  tags                       = local.tags
}

module "redis" {
  source                     = "../../modules/elasticache-redis"
  name                       = local.name
  vpc_id                     = module.networking.vpc_id
  private_subnet_ids         = module.networking.private_subnet_ids
  allowed_security_group_ids = []
  node_type                  = "cache.t4g.micro"
  num_cache_nodes            = 1
  tags                       = local.tags
}

module "kafka" {
  source                     = "../../modules/msk-kafka"
  name                       = local.name
  vpc_id                     = module.networking.vpc_id
  private_subnet_ids         = module.networking.private_subnet_ids
  allowed_security_group_ids = []
  broker_instance_type       = "kafka.t3.small"
  broker_count               = 2
  broker_ebs_volume_gb       = 50
  tags                       = local.tags
}

module "object_storage" {
  source = "../../modules/object-storage"
  name   = local.name
  tags   = local.tags
}

module "ecr" {
  source = "../../modules/ecr"
  tags   = local.tags
}

module "iam" {
  source             = "../../modules/iam"
  name               = local.name
  oidc_provider_arn  = module.eks.oidc_provider_arn
  oidc_provider_url  = module.eks.oidc_provider_url
  namespace          = "sentinelops-dev"
  secret_arns        = [module.postgres.secret_arn, module.redis.secret_arn]
  s3_bucket_arns     = values(module.object_storage.bucket_arns)
  tags               = local.tags
}

module "budget" {
  source               = "../../modules/budget-alerts"
  name                 = local.name
  monthly_budget_usd   = 200
  notification_emails  = var.budget_alert_emails
  tags                 = local.tags
}
