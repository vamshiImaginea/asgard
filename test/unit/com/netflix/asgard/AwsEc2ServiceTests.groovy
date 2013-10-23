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

import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.CreateTagsRequest
import com.amazonaws.services.ec2.model.DescribeImagesRequest
import com.amazonaws.services.ec2.model.DescribeImagesResult
import com.amazonaws.services.ec2.model.Filter
import com.amazonaws.services.ec2.model.Image
import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.SecurityGroup
import com.amazonaws.services.ec2.model.SpotInstanceRequest
import com.amazonaws.services.ec2.model.Tag
import com.google.common.collect.Multiset
import com.netflix.asgard.mock.Mocks
import com.netflix.frigga.ami.AppVersion
import grails.test.GrailsUnitTestCase
import grails.test.MockUtils

class AwsEc2ServiceTests extends GrailsUnitTestCase {

    void setUp() {
        Mocks.createDynamicMethods()
    }

 
    void testIsSecurityGroupEditable() {
        AwsEc2Service awsEc2Service = new AwsEc2Service()
        assert awsEc2Service.isSecurityGroupEditable("nf-infrastructure")
        assert awsEc2Service.isSecurityGroupEditable("nf-datacenter")
        assert !awsEc2Service.isSecurityGroupEditable("default")
        assert awsEc2Service.isSecurityGroupEditable("nccp")
    }


    void testGetImage() {
        AwsEc2Service awsEc2Service = Mocks.awsEc2Service()
        assertNull awsEc2Service.getImage(Mocks.userContext(), "doesn't exist")
    }

    
}
