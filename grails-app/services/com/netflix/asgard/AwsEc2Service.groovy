/*
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.asgard

import static com.google.common.base.Predicates.not
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY
import static org.jclouds.compute.predicates.NodePredicates.*
import static org.jclouds.ec2.options.CreateSnapshotOptions.Builder.*
import static org.jclouds.ec2.options.DescribeImagesOptions.Builder.*
import static org.jclouds.ec2.options.DescribeSnapshotsOptions.Builder.*
import static org.jclouds.ec2.options.DetachVolumeOptions.Builder.*
import static org.jclouds.ec2.domain.IpPermission.Builder.*
import groovyx.gpars.GParsExecutorsPool

import java.util.regex.Matcher
import java.util.regex.Pattern

import org.apache.commons.codec.binary.Base64
import org.codehaus.groovy.runtime.StackTraceUtils
import org.jclouds.aws.ec2.domain.SpotInstanceRequest
import org.jclouds.aws.ec2.options.CreateSecurityGroupOptions.Buidler.*
import org.jclouds.compute.ComputeService
import org.jclouds.compute.domain.ComputeMetadata
import org.jclouds.compute.domain.Image
import org.jclouds.compute.domain.NodeMetadata
import org.jclouds.compute.domain.NodeMetadata.Status;
import org.jclouds.domain.Location
import org.jclouds.ec2.EC2Client
import org.jclouds.ec2.domain.Attachment
import org.jclouds.ec2.domain.AvailabilityZoneInfo
import org.jclouds.ec2.domain.IpPermission
import org.jclouds.ec2.domain.IpProtocol;
import org.jclouds.ec2.domain.KeyPair
import org.jclouds.ec2.domain.PublicIpInstanceIdPair
import org.jclouds.ec2.domain.Reservation
import org.jclouds.ec2.domain.SecurityGroup
import org.jclouds.ec2.domain.Snapshot
import org.jclouds.ec2.domain.Subnet
import org.jclouds.ec2.domain.UserIdGroupPair
import org.jclouds.ec2.domain.Volume
import org.jclouds.ec2.features.SubnetApi
import org.jclouds.ec2.options.DescribeImagesOptions
import org.jclouds.ec2.options.DetachVolumeOptions
import org.jclouds.ec2.util.SubnetFilterBuilder
import org.springframework.beans.factory.InitializingBean

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.ec2.model.Address
import com.amazonaws.services.ec2.model.AssociateAddressRequest
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsRequest
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsResult
import com.amazonaws.services.ec2.model.CreateTagsRequest
import com.amazonaws.services.ec2.model.DeleteTagsRequest
import com.amazonaws.services.ec2.model.DescribeInstanceAttributeRequest
import com.amazonaws.services.ec2.model.DescribeInstanceAttributeResult
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryRequest
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryResult
import com.amazonaws.services.ec2.model.InstanceStateChange
import com.amazonaws.services.ec2.model.RequestSpotInstancesRequest
import com.amazonaws.services.ec2.model.RequestSpotInstancesResult
import com.amazonaws.services.ec2.model.ReservedInstances
import com.amazonaws.services.ec2.model.RunInstancesRequest
import com.amazonaws.services.ec2.model.RunInstancesResult
import com.amazonaws.services.ec2.model.Tag
import com.amazonaws.services.ec2.model.Vpc
import com.google.common.base.Predicates
import com.google.common.collect.HashMultiset
import com.google.common.collect.Lists
import com.google.common.collect.Multiset
import com.google.common.collect.TreeMultiset
import com.netflix.asgard.cache.CacheInitializer
import com.netflix.asgard.model.AutoScalingGroupData
import com.netflix.asgard.model.SecurityGroupOption
import com.netflix.asgard.model.StackAsg;
import com.netflix.asgard.model.Subnets
import com.netflix.asgard.model.ZoneAvailability
import com.netflix.frigga.ami.AppVersion

class AwsEc2Service implements CacheInitializer, InitializingBean {

	static transactional = false

	private static Pattern SECURITY_GROUP_ID_PATTERN = ~/sg-[a-f0-9]+/

	MultiRegionAwsClient<ComputeService> computeServiceClientByRegion
	def jcloudsComputeService
	Caches caches
	def configService
	def restClientService
	def taskService
	ThreadScheduler threadScheduler
	def regionService
	List<String> accounts = [] // main account is accounts[0]
	/** The state names for instances that count against reservation usage. */
	private static final List<Status> ACTIVE_INSTANCE_STATES = [Status.PENDING,Status.RUNNING]

	/** Maximum number of image ids to send in a single create tags request. See ASGARD-895. */
	private static final int TAG_IMAGE_CHUNK_SIZE = 250

	void afterPropertiesSet() {
		computeServiceClientByRegion = new MultiRegionAwsClient<ComputeService>({ Region region ->
			jcloudsComputeService.getComputeServiceForProvider(region)
		})

		accounts = configService.getAccounts()
	}
	void initialiseComputeServiceClients() {
		computeServiceClientByRegion = new MultiRegionAwsClient<ComputeService>({ Region region ->
			jcloudsComputeService.getComputeServiceForProvider(region)
		},regionService)

		accounts = configService.getAccounts()
	}

	void initializeCaches() {
		initialiseComputeServiceClients()
		initializeCachesForEachREgion()

	}
	void initializeCachesForEachREgion(){
		caches.allKeyPairs.ensureSetUp({ Region region -> retrieveKeys(region) })
		caches.allAvailabilityZones.ensureSetUp({ Region region -> retrieveAvailabilityZones(region) },{ Region region -> caches.allKeyPairs.by(region).fill() })
		caches.allImages.ensureSetUp({ Region region -> retrieveImages(region) })
		caches.allInstances.ensureSetUp({ Region region -> retrieveInstances(region) })
		caches.allSecurityGroups.ensureSetUp({ Region region -> retrieveSecurityGroups(region) })
		caches.allSnapshots.ensureSetUp({ Region region -> retrieveSnapshots(region) })
		caches.allVolumes.ensureSetUp({ Region region -> retrieveVolumes(region) })
	}

	// Availability Zones

	private Set<AvailabilityZoneInfo> retrieveAvailabilityZones(Region region) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(region).getContext());
		ec2Client.availabilityZoneAndRegionServices.describeAvailabilityZonesInRegion(region.code);
		
	}

	Collection<AvailabilityZoneInfo> getAvailabilityZones(UserContext userContext) {
		caches.allAvailabilityZones.by(userContext.region).list().sort { it.id }
	}

	Collection<AvailabilityZoneInfo> getRecommendedAvailabilityZones(UserContext userContext) {
		List<String> discouragedAvailabilityZones = configService.discouragedAvailabilityZones
		getAvailabilityZones(userContext).findAll { !(it.zone in discouragedAvailabilityZones) }
	}
	
	ComputeService getComputeService(UserContext context){
		computeServiceClientByRegion.by(context.region);
	}
	
	EC2Client getEC2Client(UserContext context){
		jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(context.region).getContext());
	}

	// Images

	private Set<Image> retrieveImages(Region region) {
		log.info 'retrieveImages in region '+ region
		Set<Image>  imagesForRegion= computeServiceClientByRegion.by(region).listImages()
		log.info 'imagesForRegion in region '+ imagesForRegion
		imagesForRegion
	}

	Collection<Image> getAccountImages(UserContext userContext) {
		caches.allImages.by(userContext.region).list()
	}

	private Collection<Subnet> retrieveSubnets(Region region) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(region).getContext());
		log.info 'subetApi Present '+ ec2Client.subnetApi.present
		//((SubnetApi)ec2Client.subnetApi.get()).filter(new SubnetFilterBuilder().availabilityZone(regionCode).build()).toList();
		return null
	}

	/**
	 * Gets information about all subnets in a region.
	 *
	 * @param userContext who, where, why
	 * @return a wrapper for querying subnets
	 */
	Subnets getSubnets(UserContext userContext) {
		Subnets.from(caches.allSubnets.by(userContext.region).list())
	}

	private Collection<Vpc> retrieveVpcs(Region region) {
		String regionCode = configService.getCloudProvider() == Provider.AWS ? region.code : "nova"
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(region).getContext());
		//computeServiceClientByRegion.by(region).describeVpcs().vpcs
		return null
	}

	/**
	 * Gets information about all VPCs in a region.
	 *
	 * @param userContext who, where, why
	 * @return a list of VPCs
	 */
	Collection<Vpc> getVpcs(UserContext userContext) {
		caches.allVpcs.by(userContext.region).list()
	}

	/**
	 * Based on a list of users and image ids, gives back a list of image objects for those ids that would be executable
	 * by any of those users.
	 *
	 * @param executableUsers Amazon account ids to check launch permissions on the image for. Will return image if it
	 *         matches any of the ids
	 * @param imageIds List of image ids to filter on
	 * @return Collection< Image > The images which match the imageIds where any of the users in executableUsers have
	 *         launch permissions
	 */
	Collection<Image> getImagesWithLaunchPermissions(UserContext userContext, Collection<String> executableUsers,
			Collection<String> imageIds) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		DescribeImagesOptions options = new DescribeImagesOptions().ownedBy(executableUsers.toArray(String[])).imageIds(imageIds);
		ec2Client.aMIServices.describeImagesInRegion(userContext.region.code, options)
	}

	/**
	 * Gets the images that have the specified package name (usually app name) if any. If the specified package name is
	 * null or empty, then this method returns all the images.
	 *
	 * @param name the package name (usually app name) to look for
	 * @return Collection< Image > the images with the specified package name, or all images if name is null or empty
	 */
	Collection<Image> getImagesForPackage(UserContext userContext, String name) {
		name ? getAccountImages(userContext).findAll { name == it.description } : getAccountImages(userContext)
	}

	Image getImage(UserContext userContext, String imageId, From preferredDataSource = From.AWS) {
		Image image = null
		if (imageId) {
			if (preferredDataSource == From.CACHE) {
				image = caches.allImages.by(userContext.region).get(imageId)
				if (image) { return image }
			}
			try {
				image = computeServiceClientByRegion.by(userContext.region).getImage(imageId)
			}
			catch (AmazonServiceException ignored) {
				// If Amazon doesn't know this image id then return null and put null in the allImages CachedMap
			}
			caches.allImages.by(userContext.region).put(imageId, image)
		}
		image
	}

	List<String> getImageLaunchers(UserContext userContext, String imageId) {
		Image image = computeServiceClientByRegion.by(userContext.region).getImage(imageId);
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

	void deregisterImage(UserContext userContext, String imageId, Task existingTask = null) {
		String msg = "Deregister image ${imageId}"
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		Closure work = { Task task ->
			Image image = getImage(userContext, imageId)
			if (image) {
				ec2Client.getAMIServices().deregisterImageInRegion(userContext.region.code,  image.providerId)
			}
			caches.allImages.by(userContext.region).remove(imageId)
		}
		taskService.runTask(userContext, msg, work, Link.to(EntityType.image, imageId), existingTask)
	}

	//mutators

	void addImageLaunchers(UserContext userContext, String imageId, List<String> userIds, Task existingTask = null) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		List<String> defaultUserGrp = new ArrayList<String>();
		defaultUserGrp.add("all");
		taskService.runTask(userContext, "Add to image ${imageId}, launchers ${userIds}", { task ->
			ec2Client.aMIServices.addLaunchPermissionsToImageInRegion(userContext.region.code, userIds, defaultUserGrp, imageId);
		}, Link.to(EntityType.image, imageId), existingTask)
		getImage(userContext, imageId)
	}

	void setImageLaunchers(UserContext userContext, String imageId, List<String> userIds) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());

		List<String> defaultUserGrp = new ArrayList<String>();
		defaultUserGrp.add("all");
		taskService.runTask(userContext, "Set image ${imageId} launchers to ${userIds}", { task ->
			ec2Client.getAMIServices().removeLaunchPermissionsFromImageInRegion(userContext.region.code, userIds, defaultUserGrp, imageId);
			ec2Client.getAMIServices().addLaunchPermissionsToImageInRegion(userContext.region.code, userIds, defaultUserGrp, imageId);
		}, Link.to(EntityType.image, imageId))
		getImage(userContext, imageId)
	}

	void createImageTags(UserContext userContext, Collection<String> imageIds, String name, String value) {
		Check.notEmpty(imageIds, "imageIds")
		Check.notEmpty(name, "name")
		Check.notEmpty(value, "value")
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		List<List<String>> partitionedImageIds = Lists.partition(imageIds as List, TAG_IMAGE_CHUNK_SIZE)
		for (List<String> imageIdsChunk in partitionedImageIds) {
			CreateTagsRequest request = new CreateTagsRequest(resources: imageIdsChunk, tags: [new Tag(name, value)])
			computeServiceClientByRegion.by(userContext.region).createTags(request)
		}
	}

	void deleteImageTags(UserContext userContext, Collection<String> imageIds, String name) {
		Check.notEmpty(imageIds, "imageIds")
		Check.notEmpty(name, "name")
		computeServiceClientByRegion.by(userContext.region).deleteTags(
				new DeleteTagsRequest().withResources(imageIds).withTags(new Tag(name))
				)
	}

	/**
	 * Adds all secondary accounts to a given image and returns the list of all those added.
	 * Fails silently if there is a permission problem adding.
	 */
	List<String> authorizeSecondaryImageLaunchers(UserContext userContext, String imageId, Task existingTask = null) {
		try {
			List<String> hasAccounts = getImageLaunchers(userContext, imageId)
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

	// Security

	Collection<KeyPair> getKeys(UserContext userContext) {
		caches.allKeyPairs.by(userContext.region).list()
	}

	private Set<KeyPair> retrieveKeys(Region region) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(region).getContext());
		ec2Client.keyPairServices.describeKeyPairsInRegion(region.code, null);
	}

	String getDefaultKeyName() {
		configService.getCloudProvider() ==Provider.AWS ? configService.defaultKeyName : configService.defaultKeyName
	}

	Collection<SecurityGroup> getSecurityGroups(UserContext userContext) {
		log.info 'list ' + caches.allSecurityGroups.by(userContext.region).list()
		caches.allSecurityGroups.by(userContext.region).list()
	}

	/**
	 * Returns a filtered and sorted list of security groups to show in UI lists. Special groups are suppressed.
	 *
	 * @param userContext who, where, why
	 * @return list of security groups
	 */
	List<SecurityGroup> getEffectiveSecurityGroups(UserContext userContext) {
		getSecurityGroups(userContext).findAll { isSecurityGroupEditable(it.name) }.sort { it.name.toLowerCase() }
	}

	Boolean isSecurityGroupEditable(String name) {
		name != 'default'
	}

	private Set<SecurityGroup> retrieveSecurityGroups(Region region) {
		log.info 'retrieveSecurityGroups in region '+ region
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(region).getContext());
		Set<SecurityGroup> securityGroups= ec2Client.securityGroupServices.describeSecurityGroupsInRegion(region.code,null)
		log.info 'retrieveSecurityGroups in region '+ securityGroups
		securityGroups
	}

	List<SecurityGroup> getSecurityGroupsForApp(UserContext userContext, String appName) {
		def pat = ~"^${appName.toLowerCase()}(-frontend)?\$"
		getSecurityGroups(userContext).findAll { it.name ==~ pat }
	}

	SecurityGroup getSecurityGroup(UserContext userContext, String name, From from = From.AWS) {
		Region region = userContext.region
		Check.notNull(name, SecurityGroup, "name")
		String groupName = name
		String groupId = ''
		Set<SecurityGroup> groups = null
		EC2Client ec2Client=null
		String regionCode = region.code
		try {
			ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(region).getContext());
			groups= ec2Client.securityGroupServices.describeSecurityGroupsInRegion(regionCode, groupName);
			return Check.lone(groups, SecurityGroup)
			//groupName = groups?.name
		} catch (IllegalStateException e) {
		log.error 'security group not found ' + e.printStackTrace()
		}
		
	}

	/**
	 * Calculates the relationships between most security groups (many potential sources of traffic) and one security
	 * group (single target of traffic).
	 *
	 * @param userContext who, where, why
	 * @param targetGroup the security group for the instances receiving traffic
	 * @return list of security group options for display
	 */
	List<SecurityGroupOption> getSecurityGroupOptionsForTarget(UserContext userContext, SecurityGroup targetGroup) {
		Collection<SecurityGroup> sourceGroups = getEffectiveSecurityGroups(userContext);
		String guessedPorts = bestIngressPortsFor(targetGroup)
		sourceGroups.collect { SecurityGroup sourceGroup ->
			buildSecurityGroupOption(sourceGroup, targetGroup, guessedPorts)
		}
	}

	/**
	 * Calculates the relationships between one security group (single source of traffic) and most security groups
	 * (many potential targets of traffic).
	 *
	 * @param userContext who, where, why
	 * @param sourceGroupName the security group for the instances sending traffic
	 * @return list of security group options for display
	 */
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
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		
		taskService.runTask(userContext, "Create Security Group ${name}", { task ->
		    ec2Client.securityGroupServices.createSecurityGroupInRegion(regionCode, name, description)
		}, Link.to(EntityType.security, name))
		getSecurityGroup(userContext, name)
	}

	void removeSecurityGroup(UserContext userContext, String name, String id) {
		String regionCode = userContext.region.code
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		taskService.runTask(userContext, "Remove Security Group ${name}", { task ->
			ec2Client.securityGroupServices.deleteSecurityGroupInRegion(regionCode, name)
		}, Link.to(EntityType.security, name))
		caches.allSecurityGroups.by(userContext.region).remove(name)
	}

	/** High-level permission update for a group pair: given the desired state, make it so. */
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
		// This method gets called hundreds of times for one user request so don't call Amazon unless necessary.
		if (somethingChanged) {
			getSecurityGroup(userContext, targetGroup.name)
		}
	}

	/** Converts a list of IpPermissions into a string representation, or null if none. */
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

	/** Returns the canonical string representation of a from-to port pair. */
	public static String portString(int fromPort, int toPort) {
		toPort == fromPort ? "${fromPort}" : "${fromPort}-${toPort}"
	}

	/** Converts a string ports representation into a list of partially populated IpPermission instances. */
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

	/** Returns the ingress permissions from one group to another. Assumes tcp and groups, not cidrs. */
	static Collection<IpPermission> getIngressFrom(SecurityGroup targetGroup, SecurityGroup sourceGroup) {
		targetGroup.ipPermissions.findAll {
			it.userIdGroupPairs.any { it.containsKey(sourceGroup.name) }
		}
	}

	private String bestIngressPortsFor(SecurityGroup targetGroup) {
		Map guess = ['7001' : 1]
		targetGroup.ipPermissions.each {
			if (it.ipProtocol == 'tcp' &&  it.userIdGroupPairs.size() > 0) {
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
		UserIdGroupPair sourcePair = new UserIdGroupPair(accounts[0],sourceGroup.name)
		String regionCode = userContext.region.code
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		taskService.runTask(userContext, "Authorize Security Group Ingress to ${groupName} from ${sourceGroupName} on ${fromPort}-${toPort}", { task ->
			ec2Client.securityGroupServices.authorizeSecurityGroupIngressInRegion(regionCode, targetgroup.name, IpProtocol.fromValue(ipProtocol),fromPort,toPort,"")
		}, Link.to(EntityType.security, groupName))
	}

	private void revokeSecurityGroupIngress(UserContext userContext, SecurityGroup targetgroup, SecurityGroup sourceGroup, String ipProtocol, int fromPort, int toPort) {
		String groupName = targetgroup.name
		String sourceGroupName = sourceGroup.name
		String regionCode = userContext.region.code
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());


		UserIdGroupPair sourcePair = new UserIdGroupPair(accounts[0],sourceGroup.id)
		List<IpPermission> perms = [
			new IpPermission()
			.withUserIdGroupPairs(sourcePair)
			.withIpProtocol(ipProtocol).withFromPort(fromPort).withToPort(toPort)
		]
		taskService.runTask(userContext, "Revoke Security Group Ingress to ${groupName} from ${sourceGroupName} on ${fromPort}-${toPort}", { task ->
			ec2Client.securityGroupServices.revokeSecurityGroupIngressInRegion(regionCode, targetgroup.id, perms)
		}, Link.to(EntityType.security, groupName))
	}

	// TODO: Delete this method after rewriting AwsResultsRetrieverSpec unit test to use some other use case
	DescribeSpotPriceHistoryResult describeSpotPriceHistory(Region region,
			DescribeSpotPriceHistoryRequest describeSpotPriceHistoryRequest) {
		computeServiceClientByRegion.by(region).describeSpotPriceHistory(describeSpotPriceHistoryRequest)
	}

	// Spot Instance Requests

	List<SpotInstanceRequest> retrieveSpotInstanceRequests(Region region) {
		//AWSEC2Client ec2Client = AWSEC2Client.class.cast(computeServiceClientByRegion.by(region).getContext().unwrap(AWSEC2ApiMetadata.CONTEXT_TOKEN).getApi());
		//ec2Client.spotInstanceServices.requestSpotInstancesInRegion(region.code, 0, 0, null, RequestSpotInstancesOptions.NONE)
		return null
	}

	DescribeSpotInstanceRequestsResult describeSpotInstanceRequests(UserContext userContext,
			DescribeSpotInstanceRequestsRequest request) {
		computeServiceClientByRegion.by(userContext.region).describeSpotInstanceRequests(request)
	}

	void createTags(UserContext userContext, CreateTagsRequest request) {
		computeServiceClientByRegion.by(userContext.region).createTags(request)
	}

	CancelSpotInstanceRequestsResult cancelSpotInstanceRequests(UserContext userContext,
			CancelSpotInstanceRequestsRequest request) {
		computeServiceClientByRegion.by(userContext.region).cancelSpotInstanceRequests(request)
	}

	RequestSpotInstancesResult requestSpotInstances(UserContext userContext, RequestSpotInstancesRequest request) {
		computeServiceClientByRegion.by(userContext.region).requestSpotInstances(request)
	}

	// Instances

	private Set<NodeMetadata> retrieveInstances(Region region) {
		Set<ComputeMetadata> listNodes = computeServiceClientByRegion.by(region).listNodes()
		Set<NodeMetadata> nodes= new HashSet<NodeMetadata>(listNodes.size())
		log.info 'retrieveInstances in region '+ region
		for(ComputeMetadata computeMetadata : listNodes){
			NodeMetadata nodeMetadata=	computeServiceClientByRegion.by(region).getNodeMetadata(computeMetadata.getId());
			nodes.add(nodeMetadata)
		}
		log.info 'retrieveInstances in region '+ nodes
		nodes
	}

	Set<NodeMetadata> getInstances(UserContext userContext) {
		caches.allInstances.by(userContext.region)?.list() ?: []
	}

	/**
	 * Gets all instances that are currently active and counted against reservation usage.
	 *
	 * @param userContext who, where, why
	 * @return Collection < Instance > active instances
	 */
	Set<NodeMetadata> getActiveInstances(UserContext userContext) {
		getInstances(userContext).findAll { it.getStatus() in ACTIVE_INSTANCE_STATES }
	}

	Set<NodeMetadata> getInstancesByIds(UserContext userContext, List<String> instanceIds, From from = From.CACHE) {
		Set<NodeMetadata> instances = []
		if (from == From.AWS) {
			instances=computeServiceClientByRegion.by(userContext.region).listNodesByIds(instanceIds)
			Map<String, NodeMetadata> instanceIdsToInstances = instances.inject([:]) { Map map, NodeMetadata instance ->
				map << [(instance.instanceId): instance]
			} as Map
			caches.allInstances.by(userContext.region).putAll(instanceIdsToInstances)
		} else if (from == From.CACHE) {
			for (String instanceId in instanceIds) {
				NodeMetadata instance = caches.allInstances.by(userContext.region).get(instanceId)
				if (instance) { instances << instance }
			}
		}
		instances
	}

	Collection<NodeMetadata> getInstancesUsingImageId(UserContext userContext, String imageId) {
		Check.notEmpty(imageId)
		getInstances(userContext).findAll { NodeMetadata instance -> instance.imageId == imageId }
	}

	/**
	 * Finds all the instances that were launched with the specified security group.
	 *
	 * @param userContext who, where, why
	 * @param securityGroup the security group for which to find relevant instances
	 * @return all the instances associated with the specified security group
	 */
	Collection<NodeMetadata> getInstancesWithSecurityGroup(UserContext userContext, SecurityGroup securityGroup) {
		getInstances(userContext).findAll {
			String name = securityGroup.name
			String id = securityGroup.id
			(name && (name in it.securityGroups*.name)) || (id && (id in it.securityGroups*.id))
		}
	}

	NodeMetadata getInstance(UserContext userContext, String instanceId, From from = From.AWS) {
		if (from == From.CACHE) {
			return caches.allInstances.by(userContext.region).get(instanceId)
		}
		computeServiceClientByRegion.by(userContext.region).getNodeMetadata(instanceId)
	}

	Multiset<AppVersion> getCountedAppVersions(UserContext userContext) {
		Map<String, Image> imageIdsToImages = caches.allImages.by(userContext.region).unmodifiable()
		getCountedAppVersionsForInstancesAndImages(getInstances(userContext), imageIdsToImages)
	}

	private Multiset<AppVersion> getCountedAppVersionsForInstancesAndImages(Collection<NodeMetadata> instances,
			Map<String, Image> images) {
		Multiset<AppVersion> appVersions = TreeMultiset.create()
		instances.each { NodeMetadata instance ->
			Image image = images.get(instance.imageId)
			AppVersion appVersion = image?.parsedAppVersion
			if (appVersion) {
				appVersions.add(appVersion)
			}
		}
		appVersions
	}

	Reservation getInstanceReservation(UserContext userContext, String instanceId) {
		Check.notNull(instanceId, Reservation, "instanceId")
		String regionCode = userContext.region.code
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());

		Set<Reservation> reservations
		try {
			reservations = ec2Client.instanceServices.describeInstancesInRegion(regionCode,instanceId);
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
	}

	void createInstanceTags(UserContext userContext, List<String> instanceIds, Map<String, String> tagNameValuePairs,
			Task existingTask = null) {
		Integer instanceCount = instanceIds.size()
		List<Tag> tags = tagNameValuePairs.collect { String key, String value -> new Tag(key, value) }
		String tagSuffix = tags.size() == 1 ? '' : 's'
		String instanceSuffix = instanceCount == 1 ? '' : 's'
		String msg = "Create tag${tagSuffix} '${tagNameValuePairs}' on ${instanceCount} instance${instanceSuffix}"
		Closure work = { Task task ->
			CreateTagsRequest request = new CreateTagsRequest().withResources(instanceIds).withTags(tags)
			computeServiceClientByRegion.by(userContext.region).createTags(request)
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
			computeServiceClientByRegion.by(userContext.region).deleteTags(request)
		}, Link.to(EntityType.instance, instanceId))
	}

	String getUserDataForInstance(UserContext userContext, String instanceId) {
		if (!instanceId) { return null }
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		ec2Client.instanceServices.getUserDataForInstanceInRegion(userContext.region.code,instanceId)		
		
	}

	Boolean checkHostHealth(String url) {
		if (configService.isOnline()) {
			Integer responseCode = restClientService.getRepeatedResponseCode(url)
			return restClientService.checkOkayResponseCode(responseCode)
		}
		true
	}

	/**
	 * Test health of instances in parallel. One failing health check stops all checks and returns false.
	 *
	 * @param healthCheckUrls of instances
	 * @return indicates if all instances are healthy
	 */
	Boolean checkHostsHealth(Collection<String> healthCheckUrls) {
		GParsExecutorsPool.withExistingPool(threadScheduler.scheduler) {
			String unhealthyHostUrl = healthCheckUrls.findAnyParallel { !checkHostHealth(it) }
			!unhealthyHostUrl
		}
	}

	List<InstanceStateChange> terminateInstances(UserContext userContext, Collection<String> instanceIds,
			Task existingTask = null) {
		Check.notEmpty(instanceIds, 'instanceIds')
		List<InstanceStateChange> terminatingInstances = []
		Closure work = { Task task ->
			Set<? extends NodeMetadata> destroyed = computeServiceClientByRegion.by(userContext.region).destroyNodesMatching(
					Predicates.<NodeMetadata> and(not(TERMINATED), withIds((String [])instanceIds.toArray())));
			getInstancesByIds(userContext, instanceIds as List, From.AWS)
			destroyed
		}
		String msg = "Terminate ${instanceIds.size()} instance${instanceIds.size() == 1 ? '' : 's'} ${instanceIds}"
		taskService.runTask(userContext, msg, work, null, existingTask)
		terminatingInstances
	}

	void rebootInstance(UserContext userContext, String instanceId) {
		taskService.runTask(userContext, "Reboot ${instanceId}", { task ->
			computeServiceClientByRegion.by(userContext.region).rebootNode(instanceId)
		}, Link.to(EntityType.instance, instanceId))
	}

	String getConsoleOutput(UserContext userContext, String instanceId) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		ec2Client.instanceServices.getConsoleOutputForInstanceInRegion(userContext.region.code, instanceId);
		
	}


	Map<String, String> describeAddresses(UserContext userContext) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		Set<PublicIpInstanceIdPair> addresses = ec2Client.getElasticIPAddressServices().describeAddressesInRegion(userContext.region.code);
		addresses.inject([:]) { Map memo, address -> memo[address.publicIp] = address.instanceId; memo } as Map
	}

	void associateAddress(UserContext userContext, String publicIp, String instanceId) {
		taskService.runTask(userContext, "Associate ${publicIp} with ${instanceId}", { task ->
			EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
			Set<PublicIpInstanceIdPair> addresses = ec2Client.getElasticIPAddressServices().associateAddressInRegion(userContext.region.code,publicIp,instanceId );	
			}, Link.to(EntityType.instance, instanceId))
	}


	private Set<Reservation> retrieveReservations(Region region) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(region).getContext());
		ec2Client.instanceServices.describeInstancesInRegion(region.code,null)
	}

	/**
	 * For a given instance type such as m1.large, this method calculates how many active instance reservations are
	 * currently available for use in each availability zone.
	 *
	 * @param userContext who, where, why
	 * @param instanceType the choice of instance type to get reservation counts for, such as m1.large
	 * @return List < ZoneAvailability > availability zones with available reservation counts, or empty list if there
	 *          are no reservations in any zones for the specified instance type
	 */
	List<ZoneAvailability> getZoneAvailabilities(UserContext userContext, String instanceType) {
		Collection<ReservedInstances> reservedInstanceGroups = getReservedInstances(userContext)
		Map<String, Integer> zonesToActiveReservationCounts = [:]
		for (ReservedInstances reservedInstanceGroup in reservedInstanceGroups) {
			if (reservedInstanceGroup.state == 'active' && reservedInstanceGroup.instanceType == instanceType) {
				String zone = reservedInstanceGroup.availabilityZone
				int runningCount = zonesToActiveReservationCounts[zone] ?: 0
				zonesToActiveReservationCounts[zone] = runningCount + reservedInstanceGroup.instanceCount
			}
		}
		Collection<NodeMetadata> activeInstances = getActiveInstances(userContext)
		Multiset<String> zoneInstanceCounts = HashMultiset.create()
		for (NodeMetadata instance in activeInstances) {
			if (instance.type.name == instanceType) {
				zoneInstanceCounts.add(instance.location.scope)
			}
		}
		Set<String> zonesWithInstances = zoneInstanceCounts.elementSet()
		Set<String> zonesWithReservations = zonesToActiveReservationCounts.keySet()
		List<String> zoneNames = (zonesWithInstances + zonesWithReservations).sort()
		List<ZoneAvailability> zoneAvailabilities = zoneNames.collect { String zone ->
			int instanceCount = zoneInstanceCounts.count(zone)
			Integer reservationCount = zonesToActiveReservationCounts[zone] ?: 0
			new ZoneAvailability(zoneName: zone, totalReservations: reservationCount, usedReservations: instanceCount)
		}
		zoneAvailabilities.any { it.totalReservations } ? zoneAvailabilities : []
	}

	Collection<ReservedInstances> getReservedInstances(UserContext userContext) {
		caches.allReservedInstancesGroups.by(userContext.region).list()
	}

	//Volumes

	Collection<Volume> getVolumes(UserContext userContext) {
		retrieveVolumes(userContext.region)
	}

	private Set<Volume> retrieveVolumes(Region region) {
		log.info 'get volumes'
		Set<Volume> volumes
		String regionCode = configService.getCloudProvider() == Provider.AWS ? region.code : "nova"
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(region).getContext());
		volumes= ec2Client.elasticBlockStoreServices.describeVolumesInRegion(regionCode,null)
		log.info 'fetched Volumes '+volumes.size()
		volumes
	}

	Volume getVolume(UserContext userContext, String volumeId, From from = From.AWS) {
		String regionCode = configService.getCloudProvider() == Provider.AWS ? userContext.region.code : "nova"
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		if (volumeId) {
			if (from == From.CACHE) {
				return caches.allVolumes.by(userContext.region).get(volumeId)
			}
			try {
				def volumes = ec2Client.elasticBlockStoreServices.describeVolumesInRegion(regionCode,volumeId )
				if (volumes.size() > 0) {
					def volume = Check.lone(volumes, Volume)
					caches.allVolumes.by(userContext.region).put(volumeId, volume)
					return volume
				}
			} catch (AmazonServiceException ase) {
				log.error("Error retrieving volume ${volumeId}", StackTraceUtils.sanitize(ase))
			}
		}
		null
	}

	void detachVolume(UserContext userContext, String volumeId, String instanceId, String device) {
		String regionCode = configService.getCloudProvider() == Provider.AWS ? userContext.region.code : "nova"
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		DetachVolumeOptions detachVolumeOptions = new  DetachVolumeOptions().fromDevice(device).fromInstance(instanceId)
		ec2Client.elasticBlockStoreServices.detachVolumeInRegion(regionCode, volumeId, false,detachVolumeOptions)
	}

	Attachment attachVolume(UserContext userContext, String volumeId, String instanceId, String device) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());		DetachVolumeOptions detachVolumeOptions = new  DetachVolumeOptions().fromDevice(device).fromInstance(instanceId)
		ec2Client.elasticBlockStoreServices.attachVolumeInRegion(userContext.region.code, volumeId, instanceId, device)

	}

	void deleteVolume(UserContext userContext, String volumeId) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		ec2Client.elasticBlockStoreServices.deleteVolumeInRegion(userContext.region.code, volumeId)
		// Do not remove it from the allVolumes map, as this prevents
		// the list page from showing volumes that are in state "deleting".
		// Volume deletes can take 20 minutes to process.
	}

	Volume createVolume(UserContext userContext, Integer size, String zone) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		def volume=	ec2Client.elasticBlockStoreServices.createVolumeInAvailabilityZone(zone, size)
		caches.allVolumes.by(userContext.region).put(volume.id, volume)
		return volume
	}

	Volume createVolumeFromSnapshot(UserContext userContext, Integer size, String zone, String snapshotId) {
		createVolume(userContext, size, zone, snapshotId)
	}

	Volume createVolume(UserContext userContext, Integer size, String zone, String snapshotId) {
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());
		def volume=	ec2Client.elasticBlockStoreServices.createVolumeFromSnapshotInAvailabilityZone(zone, size, snapshotId);
		caches.allVolumes.by(userContext.region).put(volume.id, volume)
		return volume
	}

	// Snapshots

	Collection<Snapshot> getSnapshots(UserContext userContext) {
		caches.allSnapshots.by(userContext.region).list()
	}

	private Set<Snapshot> retrieveSnapshots(Region region) {
		log.info 'retrieveSnapshots in region '+ region
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(region).getContext());
		def snapshotsInRegion = ec2Client.getElasticBlockStoreServices().describeSnapshotsInRegion(region.code,null)
		log.info 'retrieveSnapshots in region '+ snapshotsInRegion
		snapshotsInRegion

	}

	Snapshot getSnapshot(UserContext userContext, String snapshotId, From from = From.AWS) {
		String regionCode = userContext.region.code;
		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());

		if (snapshotId) {
			if (from == From.CACHE) {
				return caches.allSnapshots.by(userContext.region).get(snapshotId)
			}
			try {
				Set<Snapshot> snapshots =ec2Client.getElasticBlockStoreServices().describeSnapshotsInRegion(regionCode,snapshotIds(snapshotId))
				if (snapshots.size() > 0) {
					Snapshot snapshot = Check.lone(snapshots, Snapshot)
					caches.allSnapshots.by(userContext.region).put(snapshotId, snapshot)
					return snapshot
				}
			} catch (Exception ase) {
				log.error("Error retrieving snapshot ${snapshotId}", StackTraceUtils.sanitize(ase))
			}
		}
		null
	}

	Snapshot createSnapshot(UserContext userContext, String volumeId, String description) {

		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());

		Snapshot snapshot = null
		String msg = "Create snapshot for volume '${volumeId}' with description '${description}'"
		taskService.runTask(userContext, msg, { task ->
			snapshot = ec2Client.getElasticBlockStoreServices().createSnapshotInRegion(userContext.region.code,volumeId, withDescription(description))
			task.log("Snapshot ${snapshot.id} created")
			caches.allSnapshots.by(userContext.region).put(snapshot.id, snapshot)
		}, Link.to(EntityType.volume, volumeId))
		snapshot
	}
	
	void deleteSnapshot(UserContext userContext, String snapshotId, Task existingTask = null) {
		 String regionCode = userContext.region.code;

		EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeServiceClientByRegion.by(userContext.region).getContext());

		String msg = "Delete snapshot ${snapshotId}"
		Closure work = { Task task ->
			task.tryUntilSuccessful(
					{
						ec2Client.getElasticBlockStoreServices().deleteSnapshotInRegion(regionCode, snapshotId)
					},
					{ Exception e -> e instanceof AmazonServiceException && e.errorCode == 'InvalidSnapshot.InUse' },
					250
					)
			caches.allSnapshots.by(userContext.region).remove(snapshotId)
		}
		taskService.runTask(userContext, msg, work, Link.to(EntityType.snapshot, snapshotId), existingTask)
	}

	/**
	 * Determines the zones that should be preselected.
	 *
	 * @param availabilityZones pool of potential zones
	 * @param selectedZoneNames zones names that have previously been selected
	 * @param group an optional ASG with zone selections
	 * @return preselected zone names
	 */
	Collection<String> preselectedZoneNames(Collection<Location> availabilityZones,
			Collection<String> selectedZoneNames, AutoScalingGroupData group = null) {
		Collection<Location> preselectedAvailabilityZones = availabilityZones.findAll {
			it.shouldBePreselected(selectedZoneNames, group)
		}
		preselectedAvailabilityZones*.zoneName
	}
}
