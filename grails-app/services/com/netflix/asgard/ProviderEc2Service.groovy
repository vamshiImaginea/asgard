package com.netflix.asgard

import static com.google.common.base.Predicates.not


import static com.google.common.base.Predicates.not
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY
import static org.jclouds.compute.predicates.NodePredicates.TERMINATED
import static org.jclouds.compute.predicates.NodePredicates.inGroup
import static org.jclouds.ec2.options.CreateSnapshotOptions.Builder.*
import static org.jclouds.ec2.options.DescribeImagesOptions.Builder.*
import static org.jclouds.ec2.options.DescribeSnapshotsOptions.Builder.*
import static org.jclouds.ec2.options.DetachVolumeOptions.Builder.*

import org.jclouds.aws.ec2.AWSEC2ApiMetadata
import org.jclouds.aws.ec2.AWSEC2Client
import org.jclouds.aws.ec2.options.CreateSecurityGroupOptions.Buidler.*
import org.jclouds.compute.ComputeServiceContext
import org.jclouds.ec2.EC2Client
import org.jclouds.openstack.nova.ec2.NovaEC2ApiMetadata
import org.jclouds.openstack.nova.ec2.NovaEC2Client

class ProviderEc2Service {
	static transactional = false
	def configService
	EC2Client getProivderClient(ComputeServiceContext context){
		EC2Client ec2Client
		if(configService.getCloudProvider() == Provider.AWS){
			ec2Client = AWSEC2Client.class.cast(context.unwrap(AWSEC2ApiMetadata.CONTEXT_TOKEN).getApi());
		}else if(configService.getCloudProvider() == Provider.OPENSTACK){
			ec2Client=NovaEC2Client.class.cast(context.unwrap(NovaEC2ApiMetadata.CONTEXT_TOKEN).getApi());
		}
		ec2Client
	}

}
