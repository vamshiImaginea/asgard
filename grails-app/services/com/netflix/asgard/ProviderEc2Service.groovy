package com.netflix.asgard
import static com.google.common.base.Predicates.not
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY
import static org.jclouds.compute.predicates.NodePredicates.TERMINATED
import static org.jclouds.compute.predicates.NodePredicates.inGroup
import static org.jclouds.ec2.options.CreateSnapshotOptions.Builder.*
import static org.jclouds.ec2.options.DescribeImagesOptions.Builder.*
import static org.jclouds.ec2.options.DescribeSnapshotsOptions.Builder.*
import static org.jclouds.ec2.options.DetachVolumeOptions.Builder.*
import groovyx.gpars.GParsExecutorsPool

import java.util.regex.Matcher

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.codehaus.groovy.runtime.StackTraceUtils
import org.jclouds.aws.ec2.AWSEC2ApiMetadata
import org.jclouds.aws.ec2.AWSEC2Client
import org.jclouds.aws.ec2.options.CreateSecurityGroupOptions.Buidler.*
import org.jclouds.compute.ComputeServiceContext
import org.jclouds.compute.domain.Image
import org.jclouds.domain.Location
import org.jclouds.ec2.EC2Api
import org.jclouds.ec2.domain.Attachment
import org.jclouds.ec2.domain.AvailabilityZoneInfo
import org.jclouds.ec2.domain.IpPermission
import org.jclouds.ec2.domain.IpProtocol
import org.jclouds.ec2.domain.KeyPair
import org.jclouds.ec2.domain.PublicIpInstanceIdPair
import org.jclouds.ec2.domain.SecurityGroup
import org.jclouds.ec2.domain.Snapshot
import org.jclouds.ec2.domain.Volume
import org.jclouds.ec2.features.AMIApi
import org.jclouds.ec2.features.AvailabilityZoneAndRegionApi
import org.jclouds.ec2.features.ElasticBlockStoreApi
import org.jclouds.ec2.features.ElasticIPAddressApi
import org.jclouds.ec2.features.InstanceApi
import org.jclouds.ec2.features.KeyPairApi
import org.jclouds.ec2.features.SecurityGroupApi
import org.jclouds.ec2.options.DescribeImagesOptions
import org.jclouds.ec2.options.DetachVolumeOptions
import org.jclouds.openstack.nova.ec2.NovaEC2ApiMetadata
import org.jclouds.openstack.nova.ec2.NovaEC2Client

import com.amazonaws.AmazonServiceException
import com.netflix.asgard.model.AutoScalingGroupData
import com.netflix.asgard.model.SecurityGroupOption


class ProviderEc2Service {
	static transactional = false
	def configService
	def providerComputeService
	def restClientService
	def taskService
	def providerFeatureService


	EC2Api getProivderClient(ComputeServiceContext context){
		EC2Api ec2Api
		if(configService.getCloudProvider() == Provider.AWS){
			ec2Api = AWSEC2Client.class.cast(context.unwrap(AWSEC2ApiMetadata.CONTEXT_TOKEN).getApi());
		}else if(configService.getCloudProvider() == Provider.OPENSTACK){
			ec2Api = NovaEC2Client.class.cast(context.unwrap(NovaEC2ApiMetadata.CONTEXT_TOKEN).getApi());
		}
		ec2Api
	}




	Collection<KeyPair> getKeys(UserContext userContext) {
		caches.allKeyPairs.by(userContext.region).list()
	}

	private Set<KeyPair> retrieveKeys(Region region) {
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(region).getContext());
		((KeyPairApi)ec2Api.getKeyPairApi().get()).describeKeyPairsInRegion(region.code, null);
	}

	Set<SecurityGroup> getSecurityGroups(UserContext userContext) {
		log.info 'Retriving Security Groups for ' + userContext.region
		retrieveSecurityGroups(userContext.region)
	}


	List<SecurityGroup> getEffectiveSecurityGroups(UserContext userContext) {
		getSecurityGroups(userContext).findAll { isSecurityGroupEditable(it.name) }.sort { it.name.toLowerCase() }
	}

	Boolean isSecurityGroupEditable(String name) {
		name != 'default'
	}

	private Set<SecurityGroup> retrieveSecurityGroups(Region region) {
		log.info 'retrieveSecurityGroups in region '+ region
		Set<SecurityGroup> securityGroups = [] as Set
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(region).getContext());
		if(ec2Api){
			securityGroups= ((SecurityGroupApi)ec2Api.getSecurityGroupApi().get()).describeSecurityGroupsInRegion(region.code,null)
			log.info 'retrieveSecurityGroups in region '+ securityGroups
		}
		securityGroups
	}

	Set<SecurityGroup> getSecurityGroupsForApp(UserContext userContext, String appName) {
		String pat = ~"^${appName.toLowerCase()}(-frontend)?\$"
		getSecurityGroups(userContext).findAll { it.name ==~ pat }
	}

	SecurityGroup getSecurityGroup(UserContext userContext, String name, From from = From.AWS) {
		Region region = userContext.region
		Check.notNull(name, SecurityGroup, "name")
		String groupName = name
		String groupId = ''
		Set<SecurityGroup> groups = null
		String regionCode = region.code
		try {
			EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(region).getContext());
			groups= ((SecurityGroupApi)ec2Api.getSecurityGroupApi().get()).describeSecurityGroupsInRegion(regionCode, groupName);
			return Check.lone(groups, SecurityGroup)
			//groupName = groups?.name
		} catch (IllegalStateException e) {
			log.error 'security group not found ' + e.printStackTrace()
		}
	}

	List<SecurityGroupOption> getSecurityGroupOptionsForTarget(UserContext userContext, SecurityGroup targetGroup) {
		Collection<SecurityGroup> sourceGroups = getEffectiveSecurityGroups(userContext);
		String guessedPorts = bestIngressPortsFor(targetGroup)
		sourceGroups.collect { SecurityGroup sourceGroup ->
			buildSecurityGroupOption(sourceGroup, targetGroup, guessedPorts)
		}
	}

	List<SecurityGroupOption> getSecurityGroupOptionsForSource(UserContext userContext, SecurityGroup sourceGroup) {
		Collection<SecurityGroup> targetGroups = getEffectiveSecurityGroups(userContext)
		targetGroups.collect { SecurityGroup targetGroup ->
			String guessedPorts = bestIngressPortsFor(targetGroup)
			buildSecurityGroupOption(sourceGroup, targetGroup, guessedPorts)
		}
	}

	private SecurityGroupOption buildSecurityGroupOption(SecurityGroup sourceGroup, SecurityGroup targetGroup,
			String defaultPorts) {
		Collection<IpPermission> ipPermissions = getIngressFrom(targetGroup, sourceGroup)
		String accessiblePorts = permissionsToString(ipPermissions)
		boolean accessible = accessiblePorts ? true : false
		String ports = accessiblePorts ?: defaultPorts
		String groupName = targetGroup.name
		new SecurityGroupOption(source: sourceGroup.name, target: groupName, allowed: accessible, ports: ports)
	}

	// mutators

	SecurityGroup createSecurityGroup(UserContext userContext, String name, String description, String vpcId = null) {
		Check.notEmpty(name, 'name')
		Check.notEmpty(description, 'description')
		String groupId = null
		String regionCode = userContext.region.code
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(region).getContext());

		taskService.runTask(userContext, "Create Security Group ${name}", { task ->
			((SecurityGroupApi)ec2Api.getSecurityGroupApi().get()).createSecurityGroupInRegion(regionCode, name, description)
		}, Link.to(EntityType.security, name))
		getSecurityGroup(userContext, name)
	}

	void removeSecurityGroup(UserContext userContext, String name, String id) {
		String regionCode = userContext.region.code
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(region).getContext());
		taskService.runTask(userContext, "Remove Security Group ${name}", { task ->
			((SecurityGroupApi)ec2Api.getSecurityGroupApi().get()).deleteSecurityGroupInRegion(regionCode, name)
		}, Link.to(EntityType.security, name))
	}

	void updateSecurityGroupPermissions(UserContext userContext, SecurityGroup targetGroup, SecurityGroup sourceGroup,
			List<IpPermission> wantPerms) {
		Collection<IpPermission> havePerms = getIngressFrom(targetGroup, sourceGroup)
		if (!havePerms && !wantPerms) {
			return
		}
		Boolean somethingChanged = false
		havePerms.each { havePerm ->
			if (!wantPerms.any { wp -> wp.fromPort == havePerm.fromPort && wp.toPort == havePerm.toPort } ) {
				revokeSecurityGroupIngress(userContext, targetGroup, sourceGroup, 'tcp',
						havePerm.fromPort, havePerm.toPort)
				somethingChanged = true
			}
		}
		wantPerms.each { wantPerm ->
			if (!havePerms.any { hp -> hp.fromPort == wantPerm.fromPort && hp.toPort == wantPerm.toPort} ) {
				authorizeSecurityGroupIngress(userContext, targetGroup, sourceGroup, 'tcp',
						wantPerm.fromPort, wantPerm.toPort)
				somethingChanged = true
			}
		}
		if (somethingChanged) {
			getSecurityGroup(userContext, targetGroup.name)
		}
	}

	private static String permissionsToString(Collection<IpPermission> permissions) {
		if (permissions.size() > 0) {
			return permissions.inject('') { String result, IpPermission it ->
				def p = portString(it.fromPort, it.toPort)
				result.length() > 0 ? result + ',' + p : p
			}
		} else {
			return null
		}
	}

	public static String portString(int fromPort, int toPort) {
		toPort == fromPort ? "${fromPort}" : "${fromPort}-${toPort}"
	}

	static List<IpPermission> permissionsFromString(String portsStr) {
		List<IpPermission> perms = []
		if (portsStr) {
			portsStr.split(',').each { rangeStr ->
				Matcher m = rangeStr =~ /(-?\d+)(-(-?\d+))?/
				//println "permissionsFromString: ${portStr} => ${m[0]}"
				if (m.matches()) {
					def rangeParts = m[0]  // 0:all 1:from 2:dashAndTo 3:to
					String fromPort = rangeParts[1]
					String toPort = rangeParts[3] ?: fromPort
					perms += IpPermission.builder().fromPort(fromPort.toInteger()).toPort(toPort.toInteger())
				}
			}
		}
		perms
	}

	static Collection<IpPermission> getIngressFrom(SecurityGroup targetGroup, SecurityGroup sourceGroup) {
		targetGroup.equals(sourceGroup)?targetGroup.ipPermissions:[]

	}

	private String bestIngressPortsFor(SecurityGroup targetGroup) {
		Map guess = ['7001' : 1]

		targetGroup.ipPermissions.each() { it ->
			if (it.ipProtocol == IpProtocol.TCP) {
				Integer count = it.userIdGroupPairs.size()
				String portRange = portString(it.fromPort, it.toPort)
				guess[portRange] = guess[portRange] ? guess[portRange] + count : count
			}

		}
		String g = guess.sort { -it.value }.collect { it.key }[0]
		//println "guess: ${target.groupName} ${guess} => ${g}"
		g
	}

	// TODO refactor the following two methods to take IpPermissions List from callers now that AWS API takes those.

	private void authorizeSecurityGroupIngress(UserContext userContext, SecurityGroup targetgroup, SecurityGroup sourceGroup, String ipProtocol, int fromPort, int toPort) {
		String groupName = targetgroup.name
		String sourceGroupName = sourceGroup.name
		//	UserIdGroupPair sourcePair = new UserIdGroupPair(accounts[0],sourceGroup.name)
		String regionCode = userContext.region.code
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(region).getContext());
		taskService.runTask(userContext, "Authorize Security Group Ingress to ${groupName} from ${sourceGroupName} on ${fromPort}-${toPort}", { task ->
			((SecurityGroupApi)ec2Api.getSecurityGroupApi().get()).authorizeSecurityGroupIngressInRegion(regionCode, targetgroup.name, IpProtocol.fromValue(ipProtocol),fromPort,toPort,"")
		}, Link.to(EntityType.security, groupName))
	}

	private void revokeSecurityGroupIngress(UserContext userContext, SecurityGroup targetgroup, SecurityGroup sourceGroup, String ipProtocol, int fromPort, int toPort) {
		String groupName = targetgroup.name
		String sourceGroupName = sourceGroup.name
		String regionCode = userContext.region.code
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());


		List<IpPermission> perms = [
			IpPermission.builder().ipProtocol(IpProtocol.valueOf(ipProtocol.toUpperCase())).fromPort(fromPort).toPort(toPort).build()
		]
		taskService.runTask(userContext, "Revoke Security Group Ingress to ${groupName} from ${sourceGroupName} on ${fromPort}-${toPort}", { task ->
			((SecurityGroupApi)ec2Api.getSecurityGroupApi().get()).revokeSecurityGroupIngressInRegion(regionCode,sourceGroupName,IpProtocol.valueOf(ipProtocol.toUpperCase()),fromPort,toPort,"0.0.0.0/0")
		}, Link.to(EntityType.security, groupName))
	}









	/*	Reservation getInstanceReservation(UserContext userContext, String instanceId) {
	 Check.notNull(instanceId, Reservation, "instanceId")
	 String regionCode = userContext.region.code
	 EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
	 Set<Reservation> reservations
	 try {
	 reservations = ec2Api.instanceApi.get(). instanceServices.describeInstancesInRegion(regionCode,instanceId);
	 }
	 catch (AmazonServiceException ase) {
	 log.info "Request for instance ${instanceId} failed because ${ase}"
	 return null
	 }
	 if (reservations.size() < 1 ) {
	 log.info "Request for instance ${instanceId} failed because the instance no longer exists"
	 return null
	 }
	 Reservation reservation = Check.lone(reservations, Reservation)
	 reservation
	 }*/
	/*
	 void createInstanceTags(UserContext userContext, List<String> instanceIds, Map<String, String> tagNameValuePairs,
	 Task existingTask = null) {
	 Integer instanceCount = instanceIds.size()
	 List<Tag> tags = tagNameValuePairs.collect { String key, String value -> new Tag(key, value) }
	 String tagSuffix = tags.size() == 1 ? '' : 's'
	 String instanceSuffix = instanceCount == 1 ? '' : 's'
	 String msg = "Create tag${tagSuffix} '${tagNameValuePairs}' on ${instanceCount} instance${instanceSuffix}"
	 Closure work = { Task task ->
	 CreateTagsRequest request = new CreateTagsRequest().withResources(instanceIds).withTags(tags)
	 providerComputeService.getComputeServiceForProvider(userContext.region).createTags(request)
	 }
	 Link link = instanceIds.size() == 1 ? Link.to(EntityType.instance, instanceIds[0]) : null
	 taskService.runTask(userContext, msg, work, link, existingTask)
	 }
	 void createInstanceTag(UserContext userContext, List<String> instanceIds, String name, String value,
	 Task existingTask = null) {
	 createInstanceTags(userContext, instanceIds, [(name): value], existingTask)
	 }
	 void deleteInstanceTag(UserContext userContext, String instanceId, String name) {
	 taskService.runTask(userContext, "Delete tag '${name}' from instance ${instanceId}", { task ->
	 DeleteTagsRequest request = new DeleteTagsRequest().withResources(instanceId).withTags(new Tag(name))
	 providerComputeService.getComputeServiceForProvider(userContext.region).deleteTags(request)
	 }, Link.to(EntityType.instance, instanceId))
	 }
	 */
	String getUserDataForInstance(UserContext userContext, String instanceId) {
		if (!instanceId) { return null }
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		((InstanceApi)ec2Api.instanceApi.get()).getUserDataForInstanceInRegion(userContext.region.code,instanceId)
	}

	Boolean checkHostHealth(String url) {
		if (configService.isOnline()) {
			Integer responseCode = restClientService.getRepeatedResponseCode(url)
			return restClientService.checkOkayResponseCode(responseCode)
		}
		true
	}

	Boolean checkHostsHealth(Collection<String> healthCheckUrls) {
		GParsExecutorsPool.withExistingPool(threadScheduler.scheduler) {
			String unhealthyHostUrl = healthCheckUrls.findAnyParallel { !checkHostHealth(it) }
			!unhealthyHostUrl
		}
	}


	String getConsoleOutput(UserContext userContext, String instanceId) {
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		((InstanceApi)ec2Api.instanceApi.get()).getConsoleOutputForInstanceInRegion(userContext.region.code, instanceId);

	}


	Map<String, String> describeAddresses(UserContext userContext) {
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		Set<PublicIpInstanceIdPair> addresses = ((ElasticIPAddressApi)ec2Api.elasticIPAddressApi.get()).describeAddressesInRegion(userContext.region.code);
		addresses.inject([:]) { Map memo, address -> memo[address.publicIp] = address.instanceId; memo } as Map
	}

	void associateAddress(UserContext userContext, String publicIp, String instanceId) {
		taskService.runTask(userContext, "Associate ${publicIp} with ${instanceId}", { task ->
			EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
			Set<PublicIpInstanceIdPair> addresses =  ((ElasticIPAddressApi)ec2Api.elasticIPAddressApi.get()).associateAddressInRegion(userContext.region.code,publicIp,instanceId );
		}, Link.to(EntityType.instance, instanceId))
	}



	//Volumes

	Collection<Volume> getVolumes(UserContext userContext) {
		log.info 'get volumes'
		Set<Volume> volumes
		Region region = userContext.region
		/*		String regionCode = configService.getCloudProvider() == Provider.AWS ? region.code : "nova"
		 */		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(region).getContext());
		if(ec2Api){
			volumes= ((ElasticBlockStoreApi)ec2Api.elasticBlockStoreApi.get()).describeVolumesInRegion(region.code,null)
		}else{
			volumes	= providerFeatureService.getVolumes(userContext)

		}
		log.info 'fetched Volumes '+volumes.size()
		volumes
	}


	Volume getVolume(UserContext userContext, String volumeId) {
		String regionCode = configService.getCloudProvider() == Provider.AWS ? userContext.region.code : "nova"
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		if (volumeId) {
			if(ec2Api){
				try {
					def volumes =((ElasticBlockStoreApi)ec2Api.elasticBlockStoreApi.get()).describeVolumesInRegion(regionCode,volumeId )
					if (volumes.size() > 0) {
						def volume = Check.lone(volumes, Volume)
						return volume
					}
				} catch (AmazonServiceException ase) {
					log.error("Error retrieving volume ${volumeId}", StackTraceUtils.sanitize(ase))
				}
			}else{
				return providerFeatureService.getVolume(userContext.region, volumeId)

			}
		}
		null
	}

	void detachVolume(UserContext userContext, String volumeId, String instanceId, String device) {
		String regionCode = configService.getCloudProvider() == Provider.AWS ? userContext.region.code : "nova"
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		DetachVolumeOptions detachVolumeOptions = new  DetachVolumeOptions().fromDevice(device).fromInstance(instanceId)
		((ElasticBlockStoreApi)ec2Api.elasticBlockStoreApi.get()).detachVolumeInRegion(regionCode, volumeId, false,detachVolumeOptions)
	}

	Attachment attachVolume(UserContext userContext, String volumeId, String instanceId, String device) {
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		((ElasticBlockStoreApi)ec2Api.elasticBlockStoreApi.get()).attachVolumeInRegion(userContext.region.code, volumeId, instanceId, device)

	}

	void deleteVolume(UserContext userContext, String volumeId) {
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		if(ec2Api)
			((ElasticBlockStoreApi)ec2Api.elasticBlockStoreApi.get()).deleteVolumeInRegion(userContext.region.code, volumeId)
		else
			providerFeatureService.deleteVolume(userContext.region, volumeId)
		// Do not remove it from the allVolumes map, as this prevents
		// the list page from showing volumes that are in state "deleting".
		// Volume deletes can take 20 minutes to process.
	}

	Volume createVolume(UserContext userContext, Integer size, String zone) {
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		if(ec2Api){
			def volume=((ElasticBlockStoreApi)ec2Api.elasticBlockStoreApi.get()).createVolumeInAvailabilityZone(zone, size)
			return volume
		}
		else{
			return providerFeatureService.createVolume(userContext.region,size,RandomStringUtils.random(4), zone,null);
		}
	}

	Volume createVolumeFromSnapshot(UserContext userContext, Integer size, String zone, String snapshotId) {
		createVolume(userContext, size, zone, snapshotId)
	}

	Volume createVolume(UserContext userContext, Integer size, String zone, String snapshotId) {

		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		if(ec2Api){
			return ((ElasticBlockStoreApi)ec2Api.elasticBlockStoreApi.get()).createVolumeFromSnapshotInAvailabilityZone(zone, size, snapshotId);
		}else{
			return providerFeatureService.createVolume(userContext.region,size,RandomStringUtils.random(4), zone,null);
		}

	}

	Collection<String> preselectedZoneNames(Collection<Location> availabilityZones,
			Collection<String> selectedZoneNames, AutoScalingGroupData group = null) {
		Collection<Location> preselectedAvailabilityZones = availabilityZones.findAll {
			it.shouldBePreselected(selectedZoneNames, group)
		}
		preselectedAvailabilityZones*.zoneName
	}


	// Availability Zones

	private Set<AvailabilityZoneInfo> retrieveAvailabilityZones(Region region) {
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(region).getContext());

		if(ec2Api){
			((AvailabilityZoneAndRegionApi)ec2Api.availabilityZoneAndRegionApi.get()).describeAvailabilityZonesInRegion(region.code);
		}else{
			providerFeatureService.getAvailabilityZonesInRegion(region)

		}



	}

	Collection<AvailabilityZoneInfo> getAvailabilityZones(UserContext userContext) {
		retrieveAvailabilityZones(userContext.region)
	}

	Collection<AvailabilityZoneInfo> getRecommendedAvailabilityZones(UserContext userContext) {
		List<String> discouragedAvailabilityZones = configService.discouragedAvailabilityZones
		getAvailabilityZones(userContext).findAll { !(it.zone in discouragedAvailabilityZones) }
	}
	Collection<Image> getImagesWithLaunchPermissions(UserContext userContext, Collection<String> executableUsers,
			Collection<String> imageIds) {
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		DescribeImagesOptions options = new DescribeImagesOptions().ownedBy(executableUsers.toArray(String[])).imageIds(imageIds);
		((AMIApi)ec2Api.aMIApi.get()).describeImagesInRegion(userContext.region.code, options)
	}
	void deregisterImage(UserContext userContext, String imageId, Task existingTask = null) {
		String msg = "Deregister image ${imageId}"
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		Closure work = { Task task ->
			Image image = providerComputeService.getImage(userContext, imageId)
			if (image) {
				((AMIApi)ec2Api.aMIApi.get()).deregisterImageInRegion(userContext.region.code,  image.providerId)
			}
		}
		taskService.runTask(userContext, msg, work, Link.to(EntityType.image, imageId), existingTask)
	}

	//mutators

	void addImageLaunchers(UserContext userContext, String imageId, List<String> userIds, Task existingTask = null) {
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		List<String> defaultUserGrp = new ArrayList<String>();
		defaultUserGrp.add("all");
		taskService.runTask(userContext, "Add to image ${imageId}, launchers ${userIds}", { task ->
			((AMIApi)ec2Api.aMIApi.get()).addLaunchPermissionsToImageInRegion(userContext.region.code, userIds, defaultUserGrp, imageId);
		}, Link.to(EntityType.image, imageId), existingTask)
		providerComputeService.getImage(userContext, imageId)
	}

	void setImageLaunchers(UserContext userContext, String imageId, List<String> userIds) {
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());

		List<String> defaultUserGrp = new ArrayList<String>();
		defaultUserGrp.add("all");
		taskService.runTask(userContext, "Set image ${imageId} launchers to ${userIds}", { task ->
			((AMIApi)ec2Api.aMIApi.get()).removeLaunchPermissionsFromImageInRegion(userContext.region.code, userIds, defaultUserGrp, imageId);
			((AMIApi)ec2Api.aMIApi.get()).addLaunchPermissionsToImageInRegion(userContext.region.code, userIds, defaultUserGrp, imageId);
		}, Link.to(EntityType.image, imageId))
		providerComputeService.getImage(userContext, imageId)
	}

	/*	void createImageTags(UserContext userContext, Collection<String> imageIds, String name, String value) {
	 Check.notEmpty(imageIds, "imageIds")
	 Check.notEmpty(name, "name")
	 Check.notEmpty(value, "value")
	 EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
	 List<List<String>> partitionedImageIds = Lists.partition(imageIds as List, TAG_IMAGE_CHUNK_SIZE)
	 for (List<String> imageIdsChunk in partitionedImageIds) {
	 CreateTagsRequest request = new CreateTagsRequest(resources: imageIdsChunk, tags: [new Tag(name, value)])
	 providerComputeService.getComputeServiceForProvider(userContext.region).createTags(request)
	 }
	 }
	 void deleteImageTags(UserContext userContext, Collection<String> imageIds, String name) {
	 Check.notEmpty(imageIds, "imageIds")
	 Check.notEmpty(name, "name")
	 providerComputeService.getComputeServiceForProvider(userContext.region).deleteTags(
	 new DeleteTagsRequest().withResources(imageIds).withTags(new Tag(name))
	 )
	 }*/

	List<String> authorizeSecondaryImageLaunchers(UserContext userContext, String imageId, Task existingTask = null) {
		try {
			List<String> hasAccounts = providerComputeService.getImageLaunchers(userContext, imageId)
			hasAccounts += configService.awsAccountNumber
			List<String> addAccounts = configService.awsAccounts.findAll {account -> !hasAccounts.any {it == account}}
			if (addAccounts.size() > 0) {
				addImageLaunchers(userContext, imageId, addAccounts, existingTask)
			}
			return addAccounts
		} catch (Exception ignored) {
			return []  // permission problem
		}
	}


	// Snapshots

	Collection<Snapshot> getSnapshots(UserContext userContext) {
		retrieveSnapshots(userContext.region) as List
	}

	private Set<Snapshot> retrieveSnapshots(Region region) {
		log.info 'retrieveSnapshots in region '+ region
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(region).getContext());
		def snapshotsInRegion
		log.info 'retrieveSnapshots in region '+ snapshotsInRegion
		if(ec2Api){
			snapshotsInRegion = ((ElasticBlockStoreApi)ec2Api.elasticBlockStoreApi.get()).describeSnapshotsInRegion(region.code,null)
		}else{
			snapshotsInRegion = providerFeatureService.retrieveSnapshots(region)

		}

		snapshotsInRegion

	}

	Snapshot getSnapshot(UserContext userContext, String snapshotId, From from = From.AWS) {
		String regionCode = userContext.region.code;
		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());

		if (snapshotId) {
			if(ec2Api){
				try {
					Set<Snapshot> snapshots =((ElasticBlockStoreApi)ec2Api.elasticBlockStoreApi.get()).describeSnapshotsInRegion(regionCode,snapshotIds(snapshotId))
					if (snapshots.size() > 0) {
						Snapshot snapshot = Check.lone(snapshots, Snapshot)
						return snapshot
					}
				} catch (Exception ase) {
					log.error("Error retrieving snapshot ${snapshotId}", StackTraceUtils.sanitize(ase))
				}
			}else{
				return providerFeatureService.getSnapshot(userContext.region, snapshotId)
			}
		}
		null
	}

	Snapshot createSnapshot(UserContext userContext, String volumeId, String description) {

		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());
		if(ec2Api){
			Snapshot snapshot = null
			String msg = "Create snapshot for volume '${volumeId}' with description '${description}'"
			taskService.runTask(userContext, msg, { task ->
				snapshot = ((ElasticBlockStoreApi)ec2Api.elasticBlockStoreApi.get()).createSnapshotInRegion(userContext.region.code,volumeId, withDescription(description))
				task.log("Snapshot ${snapshot.id} created")
			}, Link.to(EntityType.volume, volumeId))
			return snapshot
		}else{
			return providerFeatureService.createSnapshot(userContext.region, volumeId, description);
		}
	}

	void deleteSnapshot(UserContext userContext, String snapshotId, Task existingTask = null) {
		String regionCode = userContext.region.code;

		EC2Api ec2Api = getProivderClient(providerComputeService.getComputeServiceForProvider(userContext.region).getContext());

		String msg = "Delete snapshot ${snapshotId}"
		Closure work = { Task task ->
			task.tryUntilSuccessful(
					{
						if(ec2Api)
							((ElasticBlockStoreApi)ec2Api.elasticBlockStoreApi.get()).deleteSnapshotInRegion(regionCode, snapshotId)
						else
							providerFeatureService.deleteSnapshot(userContext.region, snapshotId)
					},
					{e.errorCode == 'InvalidSnapshot.InUse' },
					250
					)
		}
		taskService.runTask(userContext, msg, work, Link.to(EntityType.snapshot, snapshotId), existingTask)
	}

}
