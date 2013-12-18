package com.netflix.asgard



import static com.google.common.base.Predicates.not
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY
import static org.jclouds.compute.domain.NodeMetadata.Status
import static org.jclouds.compute.predicates.NodePredicates.TERMINATED
import static org.jclouds.compute.predicates.NodePredicates.inGroup
import static org.jclouds.ec2.options.CreateSnapshotOptions.Builder.*
import static org.jclouds.ec2.options.DescribeImagesOptions.Builder.*
import static org.jclouds.ec2.options.DescribeSnapshotsOptions.Builder.*
import static org.jclouds.ec2.options.DetachVolumeOptions.Builder.*
import static org.jclouds.compute.predicates.NodePredicates.*;


import org.jclouds.ContextBuilder
import org.jclouds.aws.ec2.options.CreateSecurityGroupOptions.Buidler.*
import org.jclouds.compute.ComputeService
import org.jclouds.compute.ComputeServiceContext
import org.jclouds.compute.domain.ComputeMetadata
import org.jclouds.compute.domain.Image
import org.jclouds.compute.domain.NodeMetadata
import org.jclouds.ec2.domain.InstanceStateChange
import org.jclouds.ec2.domain.SecurityGroup
import org.jclouds.location.reference.LocationConstants
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule

import com.google.common.base.Predicates
import com.google.common.collect.ImmutableSet
import com.google.inject.Module



class ProviderComputeService {
	static transactional = false
	def configService
	def taskService

	private static final List<Status> ACTIVE_INSTANCE_STATES = [
		Status.PENDING,
		Status.RUNNING
	]

	ComputeService getComputeServiceForProvider(Region region){
		getComputeService(region,configService.getCloudProvider())
	}


	ComputeService getComputeService(Region region,Provider provider){
		ContextBuilder context = getContextBuilder(region, provider)
		return context.buildView(ComputeServiceContext.class).computeService
	}

	private ContextBuilder getContextBuilder(Region region, Provider provider) {
		Properties properties = new Properties();
		if(provider == Provider.AWS){
			properties.setProperty(PROPERTY_EC2_AMI_QUERY,"owner-id="+configService.publicResourceAccounts + configService.awsAccounts+"state=available;image-type=machine")
			if(null != region)
				properties.setProperty(LocationConstants.PROPERTY_REGIONS, region.code)
		}
		ContextBuilder context = ContextBuilder.newBuilder(null == provider.jcloudsProviderMapping ?  region.provider : provider.jcloudsProviderMapping)
				.credentials(configService.getUserName(), configService.getApiKey())
				.modules(ImmutableSet.<Module> of(new SLF4JLoggingModule()))

		if(null != region && provider == Provider.AWS){
			context.overrides(properties).endpoint("https://ec2."+region.code+".amazonaws.com")
		}
		if(provider == Provider.OPENSTACK){
			context.endpoint(configService.getEndPoint())
		}
		return context
	}

	private Set<Image> retrieveImages(Region region) {
		log.info 'retrieveImages in region '+ region
		ComputeService  computeService = getComputeServiceForProvider(region)
		Set<Image>  imagesForRegion= computeService.listImages()
		log.info 'imagesForRegion in region '+ imagesForRegion
		imagesForRegion
	}

	Collection<Image> getAccountImages(UserContext userContext) {
		retrieveImages(userContext.region)
	}

	Collection<Image> getImagesForPackage(UserContext userContext, String name) {
		name ? getAccountImages(userContext).findAll { name == it.description } : getAccountImages(userContext)
	}


	// Instances

	private Set<NodeMetadata> retrieveInstances(Region region) {
		Set<ComputeMetadata> listNodes = getComputeServiceForProvider(region).listNodes()
		Set<NodeMetadata> nodes= new HashSet<NodeMetadata>(listNodes.size())
		log.info 'retrieveInstances in region '+ region
		for(ComputeMetadata computeMetadata : listNodes){
			NodeMetadata nodeMetadata=	getComputeServiceForProvider(region).getNodeMetadata(computeMetadata.getId());
			nodes.add(nodeMetadata)
		}
		log.info 'retrieveInstances in region '+ nodes
		nodes
	}

	Set<NodeMetadata> getInstances(UserContext userContext) {
		retrieveInstances(userContext.region) ?: []
	}


	Set<NodeMetadata> getActiveInstances(UserContext userContext) {
		getInstances(userContext).findAll { it.getStatus() in ACTIVE_INSTANCE_STATES }
	}

	Set<NodeMetadata> getInstancesByIds(UserContext userContext, List<String> instanceIds) {
		getComputeServiceForProvider(userContext.region).listNodesByIds(instanceIds)
	}

	Collection<NodeMetadata> getInstancesUsingImageId(UserContext userContext, String imageId) {
		Check.notEmpty(imageId)
		getInstances(userContext).findAll { NodeMetadata instance -> instance.imageId == imageId }
	}


	Collection<NodeMetadata> getInstancesWithSecurityGroup(UserContext userContext, SecurityGroup securityGroup) {
		getInstances(userContext).findAll {
			String name = securityGroup.name
			String id = securityGroup.id
			(name && (name in it.securityGroups*.name)) || (id && (id in it.securityGroups*.id))
		}
	}

	NodeMetadata getInstance(UserContext userContext, String instanceId) {
		getComputeServiceForProvider(userContext.region).getNodeMetadata(instanceId)
	}

	Image getImage(UserContext userContext, String imageId) {
		Image image = null
		getComputeServiceForProvider(userContext.region).getImage(imageId)
	}

	List<String> getImageLaunchers(UserContext userContext, String imageId) {
		Image image = getComputeServiceForProvider(userContext.region).getImage(imageId);
		[
			image.userMetadata.get("owner")
		]
	}

	Map<String, Image> mapImageIdsToImagesForMergedInstances(UserContext userContext,
			Collection<MergedInstance> mergedInstances) {
		Map<String, Image> imageIdsToImages = new HashMap<String, Image>()
		for (MergedInstance mergedInstance : mergedInstances) {
			String imageId = mergedInstance?.amiId
			if (!(imageId in imageIdsToImages.keySet())) {
				imageIdsToImages.put(imageId, getImage(userContext, imageId, From.CACHE))
			}
		}
		imageIdsToImages
	}

	List<InstanceStateChange> terminateInstances(UserContext userContext, Collection<String> instanceIds,
			Task existingTask = null) {
		Check.notEmpty(instanceIds, 'instanceIds')
		List<InstanceStateChange> terminatingInstances = []
		Closure work = { Task task ->
			Set<? extends NodeMetadata> destroyed = getComputeServiceForProvider(userContext.region).destroyNodesMatching(
					Predicates.<NodeMetadata> and(not(TERMINATED), withIds((String [])instanceIds.toArray())));
			getInstancesByIds(userContext, instanceIds as List)
			destroyed
		}
		String msg = "Terminate ${instanceIds.size()} instance${instanceIds.size() == 1 ? '' : 's'} ${instanceIds}"
		taskService.runTask(userContext, msg, work, null, existingTask)
		terminatingInstances
	}

	void rebootInstance(UserContext userContext, String instanceId) {
		taskService.runTask(userContext, "Reboot ${instanceId}", { task ->
			getComputeServiceForProvider(userContext.region).rebootNode(instanceId)
		}, Link.to(EntityType.instance, instanceId))
	}

}
