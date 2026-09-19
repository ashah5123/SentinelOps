# Production environment: one NAT gateway per AZ, Multi-AZ RDS, a 2-node Redis replication
# group with automatic failover, a 3-broker MSK cluster, and a larger EKS node group.
#
# This configuration was written and reviewed but NOT applied — `terraform apply` was never run
# against a real AWS account for this phase (no cloud credentials or explicit authorization were
# provided, and this phase's own instructions prohibit provisioning paid cloud resources without
# explicit authorization). See docs/development/production-deployment.md for the exact
# review/apply procedure a team would follow before a real production rollout.

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

  # backend "s3" {
  #   bucket         = "sentinelops-terraform-state-production"
  #   key            = "production/terraform.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "sentinelops-terraform-locks-production"
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
  name = "sentinelops-production"
  tags = {
    Project     = "sentinelops"
    Environment = "production"
    ManagedBy   = "terraform"
    Owner       = var.owner_tag
    CostCenter  = var.cost_center_tag
  }
}

module "networking" {
  source             = "../../modules/networking"
  name               = local.name
  vpc_cidr           = "10.40.0.0/16"
  azs                = var.azs
  single_nat_gateway = false # one NAT per AZ — a single NAT gateway is a single point of failure
  tags               = local.tags
}

module "eks" {
  source              = "../../modules/eks"
  name                = local.name
  vpc_id              = module.networking.vpc_id
  private_subnet_ids  = module.networking.private_subnet_ids
  node_instance_types = ["m6i.large"]
  node_min_size       = 3
  node_max_size       = 12
  node_desired_size   = 3
  tags                = local.tags
}

module "postgres" {
  source                     = "../../modules/rds-postgres"
  name                       = local.name
  vpc_id                     = module.networking.vpc_id
  private_subnet_ids         = module.networking.private_subnet_ids
  allowed_security_group_ids = [] # populate with the EKS node security group once known post-apply
  instance_class             = "db.r6g.large"
  allocated_storage_gb       = 200
  multi_az                   = true
  backup_retention_days      = 30
  deletion_protection        = true
  tags                       = local.tags
}

module "redis" {
  source                     = "../../modules/elasticache-redis"
  name                       = local.name
  vpc_id                     = module.networking.vpc_id
  private_subnet_ids         = module.networking.private_subnet_ids
  allowed_security_group_ids = []
  node_type                  = "cache.r6g.large"
  num_cache_nodes            = 2
  tags                       = local.tags
}

module "kafka" {
  source                     = "../../modules/msk-kafka"
  name                       = local.name
  vpc_id                     = module.networking.vpc_id
  private_subnet_ids         = module.networking.private_subnet_ids
  allowed_security_group_ids = []
  broker_instance_type       = "kafka.m5.large"
  broker_count               = 3
  broker_ebs_volume_gb       = 500
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
  source            = "../../modules/iam"
  name              = local.name
  oidc_provider_arn = module.eks.oidc_provider_arn
  oidc_provider_url = module.eks.oidc_provider_url
  namespace         = "sentinelops"
  secret_arns       = [module.postgres.secret_arn, module.redis.secret_arn]
  s3_bucket_arns    = values(module.object_storage.bucket_arns)
  tags              = local.tags
}

module "budget" {
  source              = "../../modules/budget-alerts"
  name                = local.name
  monthly_budget_usd  = 2000
  notification_emails = var.budget_alert_emails
  tags                = local.tags
}
