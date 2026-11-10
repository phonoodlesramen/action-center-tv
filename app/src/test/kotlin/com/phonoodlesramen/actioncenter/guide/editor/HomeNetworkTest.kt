package com.phonoodlesramen.actioncenter.guide.editor

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which of the active network's addresses the phone editor shows. */
class HomeNetworkTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun `the first IPv4 address a phone can reach`() {
        val picked = HomeNetwork.pick(listOf(ip("fe80::1"), ip("2001:db8::1"), ip("127.0.0.1"), ip("169.254.3.4"), ip("192.168.1.20"), ip("10.0.0.2")))
        assertEquals(ip("192.168.1.20"), picked)
    }

    @Test
    fun `none without an IPv4 address on the network`() {
        assertNull(HomeNetwork.pick(listOf(ip("2001:db8::1"), ip("127.0.0.1"), ip("0.0.0.0"))))
        assertNull(HomeNetwork.pick(emptyList()))
    }
}
