
package com.netflix.asgard

/**
 * A way to indicate a choice of Provider like AWS or OpenStack
 */
enum Provider {

	AWS('aws',
	'aws-ec2'
	),

	OPENSTACK('openstack',
	'openstack-nova-ec2'
	)

	String providerName
	String jcloudsProviderMapping


	Provider(String providerName, String jcloudsProviderMapping) {
		this.providerName = providerName
		this.jcloudsProviderMapping = jcloudsProviderMapping
	}


	static Provider withCode(String providerName) {
		Provider.values().find { it.providerName.equalsIgnoreCase(providerName) } as Provider
	}



	static Provider defaultProvider() {
		Provider.AWS
	}

	String getDescription() {
		"$providerName"
	}

	String toString() {
		providerName
	}
}
