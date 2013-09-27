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

import org.jclouds.ContextBuilder
import org.jclouds.aws.ec2.AWSEC2ApiMetadata
import org.jclouds.aws.ec2.AWSEC2Client
import org.jclouds.aws.ec2.options.CreateSecurityGroupOptions.Buidler.*
import org.jclouds.compute.ComputeService
import org.jclouds.compute.ComputeServiceContext
import org.jclouds.ec2.EC2Client
import org.jclouds.ec2.domain.Volume
import org.jclouds.location.reference.LocationConstants
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule
import org.jclouds.openstack.nova.ec2.NovaEC2ApiMetadata
import org.jclouds.openstack.nova.ec2.NovaEC2Client
import org.jclouds.openstack.nova.v2_0.NovaApi
import org.jclouds.openstack.nova.v2_0.NovaApiMetadata
import org.jclouds.openstack.nova.v2_0.extensions.VolumeApi

import com.google.common.collect.ImmutableSet
import com.google.inject.Module

class JcloudsComputeService {

	static transactional = false
	def configService

	ComputeService getComputeServiceForProvider(Region region){
		ComputeService computeService = null
		log.info 'current provider ' + configService.getProvider().toString()
		if(configService.getCloudProvider() == Provider.AWS){
			computeService = getAWSComputeService(region)
		}else{
			computeService = getOpenStackComputeService(region)
		}
		computeService
	}


	ComputeService getAWSComputeService(Region region){
		Properties properties = new Properties();
		properties.setProperty(PROPERTY_EC2_AMI_QUERY,"owner-id="+configService.publicResourceAccounts + configService.awsAccounts+"state=available;image-type=machine")
		properties.setProperty(LocationConstants.PROPERTY_REGIONS, region.code)
		ComputeServiceContext context = ContextBuilder.newBuilder(Provider.AWS.jcloudsProviderMapping).overrides(properties).credentials("AKIAJ7RCWJDAISNDTA3Q", "zZyEoH/9yYK0lFcf6/dvcLvzs6+/VzcPghE0uN/1")
				.modules(ImmutableSet.<Module> of(new SLF4JLoggingModule()))
				.endpoint("https://ec2."+region.code+".amazonaws.com")
				.buildView(ComputeServiceContext.class);
		context.getComputeService();
	}


	ComputeService getOpenStackComputeService(Region region){
		ComputeServiceContext context = ContextBuilder.newBuilder(Provider.OPENSTACK.jcloudsProviderMapping)
				.endpoint(configService.getOpenStackEndPoint())
				.credentials(configService.getOpenStackUser(), configService.getOpenStackPassword())
				.modules(ImmutableSet.<Module> of(new SLF4JLoggingModule()))
				.buildView(ComputeServiceContext.class);
		context.getComputeService();
	}


	EC2Client getProivderClient(ComputeServiceContext context){
		EC2Client ec2Client
		if(configService.getCloudProvider() == Provider.AWS){
			ec2Client = AWSEC2Client.class.cast(context.unwrap(AWSEC2ApiMetadata.CONTEXT_TOKEN).getApi());
		}else{
			ec2Client=NovaEC2Client.class.cast(context.unwrap(NovaEC2ApiMetadata.CONTEXT_TOKEN).getApi());
		}
		ec2Client
	}
}
