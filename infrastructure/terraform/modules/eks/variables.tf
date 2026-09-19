variable "name" {
  type        = string
  description = "Cluster name, e.g. \"sentinelops-production\"."
}

variable "kubernetes_version" {
  type        = string
  default     = "1.31"
  description = "EKS control-plane Kubernetes version."
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Worker nodes and the control-plane ENIs live only in private subnets — no node ever gets a public IP."
}

variable "node_instance_types" {
  type        = list(string)
  default     = ["t3.medium"]
  description = "Low-cost default for dev; override to e.g. [\"m6i.large\"] for production."
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_max_size" {
  type    = number
  default = 6
}

variable "node_desired_size" {
  type    = number
  default = 2
}

variable "enable_cluster_encryption" {
  type        = bool
  default     = true
  description = "Encrypts Kubernetes Secrets at rest in etcd using the KMS key this module creates."
}

variable "tags" {
  type    = map(string)
  default = {}
}
