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

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

import org.jclouds.compute.domain.NodeMetadata

import com.amazonaws.services.ec2.model.Tag
import com.netflix.asgard.model.ApplicationInstance

/**
 * Generic Instance encapsulation for use in instance list. May be created from:
 *   - Discovery's Application*ApplicationInstance
 * and/or
 *   - EC2's RegisteredInstance
 */
@EqualsAndHashCode
@ToString
class MergedInstance {

    // General fields
    String appName
    String hostName    // ec2 public, or Netflix DC private host name
    String status

    // EC2 only
    String instanceType
    String instanceId
    String amiId
    String zone
    Date launchTime

    // Discovery only
    String version
    String port

    String launchConfigurationName

    NodeMetadata ec2Instance
    Collection<ApplicationInstance> appInstances

    MergedInstance() {
    }

    MergedInstance(NodeMetadata ec2Instance, Collection<ApplicationInstance> appInstances) {
        this.ec2Instance = ec2Instance
        this.appInstances = appInstances       

        // EC2 Instance fields
        if (ec2Instance) {
            hostName      = ec2Instance.hostname
            status        = ec2Instance.status
            instanceId    = ec2Instance.id
            amiId         = ec2Instance.imageId
            instanceType  = ec2Instance.hardware?.providerId?:ec2Instance.type
            zone          = ec2Instance.location
            launchTime    = new Date()
        }
    }

    String getVipAddress() {
        appInstances?.vipAddress
    }

    List<Tag> listTags() {
        ec2Instance?.tags?.sort { it.key }
    }

    List listFieldContainers() {
        [
                this,
                appInstances,
                appInstances?.dataCenterInfo,
                appInstances?.leaseInfo,
                ec2Instance,
                ec2Instance?.placement,
                ec2Instance?.state,
                ec2Instance?.blockDeviceMappings?.collect { [it, it.ebs] }
        ].flatten().findAll { it != null }
    }

    List<String> listFieldNames() {
        List<String> keyNames = listFieldContainers().collect {
            it instanceof Map ? it.keySet() : it.metaClass?.properties*.name
        }.flatten().unique()
        keyNames.findAll { it && !BeanState.isMetaGarbagePropertyName(it) }.sort()
    }

    String getFieldValue(String fieldName) {
        if (!fieldName) { return null }
        def container = listFieldContainers().find { it.metaClass?.hasProperty(it, fieldName) ||
                (it instanceof Map && it.containsKey(fieldName))
        }
        return container ? container[fieldName] : null
    }

    def attributes() {
        [ami: amiId, app: appName, id: instanceId, launch: launchTime, status: status, version: version, zone: zone]
    }

}
