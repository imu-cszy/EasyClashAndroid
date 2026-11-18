package com.github.kr328.clash.service.easytier

import com.github.kr328.clash.core.easytier.EasyTierJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**模拟器实测抓到的真实 JSON**做回归测试。
 *
 * 2026-11-18 教训：按 proto 直觉写的模型（address 为字符串、prefix_len）与真实输出
 * （address.addr 为 u32、network_length）不符，会导致 collectNetworkInfos 解析失败、
 * 状态机永远停在 WAITING_FOR_PEER。此测试锁定真实结构。
 */
class EasyTierJsonTest {
    private val realJson = """
        {"dev_name":"","my_node_info":{"virtual_ipv4":{"address":{"addr":176061972},"network_length":24},
        "hostname":"smoke-test","version":"2.6.4-8428a89d~","ips":{"public_ipv4":null,
        "interface_ipv4s":[{"addr":167772688}],"public_ipv6":null,"interface_ipv6s":[],
        "listeners":[]},"stun_info":{"udp_nat_type":0,"tcp_nat_type":0,"last_update_time":0,
        "public_ip":[],"min_port":0,"max_port":0},"listeners":[{"url":"ring://44abaa25-19ec-45af-9601-5cf9cb69f776"}],
        "vpn_portal_cfg":"ERROR: Wireguard VPN Portal Not Started","peer_id":4053611235},
        "events":["{\"time\":\"2026-11-18T13:38:44Z\",\"event\":{\"ListenerAdded\":\"ring://x\"}}"],
        "routes":[],"peers":[],"peer_route_pairs":[],"running":true,"error_msg":null,
        "foreign_network_summary":{"info_map":{}}}
    """.trimIndent()

    @Test
    fun `parses real running info from emulator`() {
        val map = EasyTierJson.parseRunningInfoMap("""{"map":{"smoke-test":$realJson}}""")
        val info = map.map["smoke-test"]
        assertNotNull(info)
        info!!
        assertTrue(info.running)
        assertNull(info.errorMsg)
        assertEquals("smoke-test", info.myNodeInfo?.hostname)
        // u32 176061972 = 0x0A7E7E14 = 10.126.126.20
        assertEquals("10.126.126.20/24", info.virtualIpv4)
        assertEquals(1, info.events.size)
        assertTrue(info.peers.isEmpty())
    }

    @Test
    fun `parses peers routes and p2p tunnel`() {
        val json = """
            {"map":{"inst":{"running":true,"my_node_info":{"virtual_ipv4":{"address":{"addr":176061972},"network_length":24}},
            "routes":[{"peer_id":42,"ipv4_addr":{"address":{"addr":176061962},"network_length":24},
                       "hostname":"lightsail","path_latency":12,"next_hop_peer_id":42,"version":"2.6.4"}],
            "peers":[{"peer_id":42,"conns":[{"conn_id":"c1","tunnel":{"tunnel_type":"p2p","remote_addr":{"url":"udp://1.2.3.4:11010"}},
                       "stats":{"rx_bytes":100,"tx_bytes":200,"latency_us":12345},"loss_rate":0.5,"is_closed":false}],
                       "default_conn_id":"c1"}],
            "peer_route_pairs":[{"route":{"peer_id":42,"ipv4_addr":{"address":{"addr":176061962},"network_length":24},
                                  "hostname":"lightsail","path_latency":12},
                                 "peer":{"peer_id":42,"conns":[{"conn_id":"c1","tunnel":{"tunnel_type":"p2p"},
                                        "stats":{"rx_bytes":100,"tx_bytes":200,"latency_us":12345}}]}}]}}}
        """.trimIndent()
        val info = EasyTierJson.parseRunningInfoMap(json).map["inst"]!!
        assertEquals(1, info.peers.size)
        assertEquals(1, info.routes.size)
        assertEquals("10.126.126.10/24", info.routes[0].ipv4)
        assertEquals(12, info.routes[0].pathLatencyMs)
        val conn = info.peers[0].conns[0]
        assertEquals("p2p", conn.tunnelType)
        assertEquals(true, conn.isP2p)
        assertEquals(12345L, conn.stats?.latencyUs)
        assertEquals("udp://1.2.3.4:11010", conn.remoteAddr)
        assertEquals(1, info.peerRoutePairs.size)
        assertEquals("lightsail", info.peerRoutePairs[0].route?.hostname)
    }

    @Test
    fun `malformed json degrades to empty map instead of throwing`() {
        assertEquals(0, EasyTierJson.parseRunningInfoMap("not json").map.size)
        assertEquals(0, EasyTierJson.parseRunningInfoMap("""{"nope":1}""").map.size)
        assertEquals(0, EasyTierJson.parseRunningInfoMap("""{"map":{"x":"not-an-object"}}""").map.size)
    }

    @Test
    fun `missing optional fields do not throw`() {
        val info = EasyTierJson.parseRunningInfoMap("""{"map":{"i":{"running":true}}}""").map["i"]!!
        assertNull(info.virtualIpv4)
        assertTrue(info.peers.isEmpty())
        assertEquals("", info.devName)
    }
}
