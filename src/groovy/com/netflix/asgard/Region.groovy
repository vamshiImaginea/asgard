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

import java.net.URI;
import java.util.Iterator;
import java.util.Set;
import java.util.Map.Entry;

import groovy.transform.EqualsAndHashCode;
import groovy.transform.Immutable;

import org.jclouds.compute.ComputeService
import org.jclouds.ec2.EC2Client;

import com.google.common.cache.LocalCache.Values;

/**
 * A way to indicate a choice of region within the Amazon Web Services global service offering.
 */
@Immutable
@EqualsAndHashCode
class Region {

	static final Region US_EAST_1 = new Region(code:'us-east-1')
	static final List<Region> RACKSPACE_SERVER_REGIONS  = [new Region(provider:'rackspace-cloudservers-us',code:'US'),
		new Region(provider:'rackspace-cloudservers-uk',code:'UK')]

	String code
	String endpoint
	String provider

	static List<Region> values(){
		[]
	}
	
	/**
	 * Takes a canonical identifier for an AWS region and returns the matching Region object. If no match exists, this
	 * method returns null.
	 *
	 * @pgaram code a String such as us-east-1 or ap-southeast-1
	 * @return Region a matching Region object, or null if no match found
	 */
	static Region withCode(String code) {
		Region.values().find { it.code == code } as Region
	}

	/**
	 * Takes a region identifier used in Amazon's pricing JSON data and returns the matching Region object.
	 * If no match exists, this method returns null.
	 *
	 * @param jsonPricingCode a String such as us-east or apac-tokyo
	 * @return Region a matching Region object, or null if no match found
	 */
	static Region withPricingJsonCode(String pricingJsonCode) {
		Region.US_EAST_1
	}

	/**
	 * There are times (such as during development) when it is useful to only use a subset of regions by specifying a
	 * system property.
	 *
	 * @return List < Region > subset of regions if "onlyRegions" system property is specified, otherwise an empty list
	 */
	static List<Region> getLimitedRegions() {
		String onlyRegions = System.getProperty('onlyRegions')
		if (onlyRegions) {
			List<String> regionNames = onlyRegions.tokenize(',')
			return regionNames.collect { Region.withCode(it) }
		}
		[]
	}
	static Region defaultRegion() { Region.US_EAST_1 }

	String getDescription() {
		code
	}

	String toString() { code }
}