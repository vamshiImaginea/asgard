package com.netflix.asgard

import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.jclouds.compute.ComputeService
import org.jclouds.ec2.EC2Client

class RegionService {
	static transactional = false
	def configService
	def jcloudsComputeService
	static boolean reloadRegions = true
	 List<Region> regions = []
	List<Region> values(){
		if(reloadRegions){
			regions.clear()
			if(configService.appConfigured){
				ComputeService computeService = jcloudsComputeService.getComputeServiceForProvider(null)
				EC2Client ec2Client = jcloudsComputeService.getProivderClient(computeService.getContext())
				Set<Entry<String, URI>> entry = ec2Client.getAvailabilityZoneAndRegionServices().describeRegions(null).entrySet();
				for (Iterator iterator = entry.iterator(); iterator.hasNext();) {
					Entry<String, URI> regionsEntrySet = (Entry<String, URI>) iterator.next();
					regions.add(new Region(code:regionsEntrySet.getKey(),endpoint:regionsEntrySet.getValue()))


				}
			}
			reloadRegions = false
		}
		regions

	}
	Region withCode(String code) {
		if(null!=regions)
			regions.find { it.code == code } as Region
		else
			Region.US_EAST_1
	}

}
