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
package com.netflix.asgard.model

import com.netflix.asgard.Region
import com.netflix.asgard.Time
import com.netflix.asgard.cache.Fillable
import org.apache.commons.logging.LogFactory

class MultiRegionInstancePrices implements Fillable {
	private static final log = LogFactory.getLog(this)

	final String name
	private Closure retriever
	private Boolean doingFirstFill = true


	private MultiRegionInstancePrices(String name) {
		this.name = name
	}

	static MultiRegionInstancePrices create(String name) {
		new MultiRegionInstancePrices(name)
	}

	/**
	 * Initializes cache if not already done.
	 *
	 * @param retriever a closure containing the algorithm for loading the pricing data
	 */
	void ensureSetUp(Closure retriever) {
		this.retriever = retriever
	}

	void fill() {

	}

	boolean isFilled() {
		!doingFirstFill
	}


	@Override
	public void removeCachedEntries() {
		doingFirstFill = true
	}
}
