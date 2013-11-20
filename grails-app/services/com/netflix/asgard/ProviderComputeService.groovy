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
class ProviderComputeService {
	static transactional = false
	def configService

	ComputeService getComputeServiceForProvider(Region region){
		log.info 'current provider ' + configService.getProvider().toString()
		log.info 'current jcloudsProviderMapping ' + configService.getCloudProvider().jcloudsProviderMapping
		log.info 'current region ' + region
		getComputeService(region,configService.getCloudProvider())
	}


	ComputeService getComputeService(Region region,Provider provider){
		Properties properties = new Properties();
		if(provider == Provider.AWS){
			properties.setProperty(PROPERTY_EC2_AMI_QUERY,"owner-id="+configService.publicResourceAccounts + configService.awsAccounts+"state=available;image-type=machine")
			if(null != region)
				properties.setProperty(LocationConstants.PROPERTY_REGIONS, region.code)
		}
		ContextBuilder context = ContextBuilder.newBuilder(null == provider.jcloudsProviderMapping ?  region.provider : provider.jcloudsProviderMapping)
				.credentials(configService.getUserName(), configService.getApiKey())
				.modules(ImmutableSet.<Module> of(new SLF4JLoggingModule()))

		log.info 'current provider credentials' + configService.getEndPoint()
		if(null != region && provider == Provider.AWS){
			context.overrides(properties).endpoint("https://ec2."+region.code+".amazonaws.com")
		}
		if(provider == Provider.OPENSTACK){
			context.endpoint(configService.getEndPoint())
		}


		return context.buildView(ComputeServiceContext.class).getComputeService()
	}
}
