variable "name" {
  description = "Name prefix for every resource this module creates, e.g. \"sentinelops-production\"."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "azs" {
  description = "Availability zones to spread subnets across. Use at least 2 for production; 1 is acceptable for a low-cost dev environment."
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b"]
}

variable "single_nat_gateway" {
  description = "Use one NAT gateway for all private subnets instead of one per AZ. true is the low-cost dev choice; false (one per AZ) is the production-recommended, more resilient choice."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Common resource tags applied to every resource in this module (see the root module's cost-allocation tagging strategy)."
  type        = map(string)
  default     = {}
}
