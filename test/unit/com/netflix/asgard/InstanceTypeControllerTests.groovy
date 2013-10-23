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

import com.netflix.asgard.mock.Mocks
import com.netflix.asgard.model.InstanceTypeData
import grails.test.mixin.TestFor
import org.junit.Before

@TestFor(InstanceTypeController)
class InstanceTypeControllerTests {

    @Before
    void setUp() {
        TestUtils.setUpMockRequest()
        controller.instanceTypeService = Mocks.instanceTypeService()
    }
    void testList() {
        def attrs = controller.list()
        List<InstanceTypeData> types = attrs.instanceTypes as List
		println types
        assert 3 == types.size()
        assert 'large' == types[0].hardware.id
		assert 'medium' == types[1].hardware.id
		assert 'small' == types[2].hardware.id
		assert 15360 == types[0].hardware.ram
		assert 7680 == types[1].hardware.ram
		assert 1740 == types[2].hardware.ram
		

    }
}
