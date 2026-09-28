/*

Copyright (c) 2008, Arvid Norberg
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in
      the documentation and/or other materials provided with the distribution.
    * Neither the name of the author nor the names of its
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

*/

#include "libtorrent/session.hpp"
#include "libtorrent/settings_pack.hpp"
#include "libtorrent/alert_types.hpp"
#include "libtorrent/time.hpp" // for clock_type
#include "libtorrent/aux_/utp_stream.hpp"
#include "libtorrent/session_stats.hpp"

#include "test.hpp"
#include "utils.hpp"
#include "setup_swarm.hpp"
#include "settings.hpp"
#include <fstream>
#include <iostream>
#include <tuple>
#include <vector>

#include "simulator/packet.hpp"

using namespace lt;

namespace {

struct pppoe_config final : sim::default_config
{
	int path_mtu(address, address) override
	{
		// this is the size left after IP and UDP headers are deducted
		return 1464;
	}
};

std::int64_t metric(std::vector<std::int64_t> const& counters, char const* key)
{
	auto const idx = lt::find_metric_idx(key);
	return (idx < 0) ? -1 : counters[idx];
}

std::vector<std::int64_t> utp_test(sim::configuration& cfg, int send_buffer_size = 0)
{
	sim::simulation sim{cfg};

	std::vector<std::int64_t> cnt;

	setup_swarm(2, swarm_test::upload | swarm_test::large_torrent | swarm_test::no_auto_stop, sim
		// add session
		, [&](lt::settings_pack& pack) {
		// force uTP connection
			utp_only(pack);
			if (send_buffer_size != 0)
				pack.set_int(settings_pack::send_socket_buffer_size, send_buffer_size);
		}
		// add torrent
		, [](lt::add_torrent_params& params) {
			params.flags |= torrent_flags::seed_mode;
		}
		// on alert
		, [&](lt::alert const* a, lt::session& ses) {
			if (auto ss = alert_cast<session_stats_alert>(a))
				cnt.assign(ss->counters().begin(), ss->counters().end());
		}
		// terminate
		, [&](int const ticks, lt::session& s) -> bool
		{
			if (ticks == 100)
				s.post_session_stats();

			if (ticks > 100)
			{
				if (is_seed(s)) return true;

				TEST_ERROR("timeout");
				return true;
			}
			return false;
		});
	return cnt;
}
}

// TODO: 3 simulate non-congestive packet loss
// TODO: 3 simulate unpredictable latencies
// TODO: 3 simulate proper (taildrop) queues (perhaps even RED and BLUE)

// The counters checked by these tests are proxies for the expected behavior. If
// they change, ensure the utp log and graph plot by parse_utp_log.py look good
// still!

TORRENT_TEST(utp_pmtud)
{
#if TORRENT_UTP_LOG
	lt::aux::set_utp_stream_logging(true);
#endif

	pppoe_config cfg;

	std::vector<std::int64_t> cnt = utp_test(cfg);

	// This is the one MTU probe that's lost. Note that fast-retransmit packets
	// (nor MTU-probes) are treated as congestion. Only packets treated as
	// congestion count as utp_packet_loss.
	TEST_EQUAL(metric(cnt, "utp.utp_fast_retransmit"), 2);
	TEST_EQUAL(metric(cnt, "utp.utp_packet_resend"), 2);

	TEST_EQUAL(metric(cnt, "utp.utp_packet_loss"), 0);

	TEST_EQUAL(metric(cnt, "utp.utp_timeout"), 0);

	TEST_EQUAL(metric(cnt, "utp.utp_packets_in"), 593);
	TEST_EQUAL(metric(cnt, "utp.utp_payload_pkts_in"), 72);

	TEST_EQUAL(metric(cnt, "utp.utp_packets_out"), 604);

	// we don't expect any invalid packets, since we're talking to ourself
	TEST_EQUAL(metric(cnt, "utp.utp_invalid_pkts_in"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_redundant_pkts_in"), 0);
}

TORRENT_TEST(utp_plain)
{
#if TORRENT_UTP_LOG
	lt::aux::set_utp_stream_logging(true);
#endif

	// the available bandwidth is so high the test never bumps up against it
	sim::default_config cfg;

	std::vector<std::int64_t> cnt = utp_test(cfg);

	TEST_EQUAL(metric(cnt, "utp.utp_packet_loss"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_timeout"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_fast_retransmit"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_packet_resend"), 0);

	TEST_EQUAL(metric(cnt, "utp.utp_packets_in"), 590);
	TEST_EQUAL(metric(cnt, "utp.utp_payload_pkts_in"), 77);

	TEST_EQUAL(metric(cnt, "utp.utp_packets_out"), 598);

	// we don't expect any invalid packets, since we're talking to ourself
	TEST_EQUAL(metric(cnt, "utp.utp_invalid_pkts_in"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_redundant_pkts_in"), 0);
}

TORRENT_TEST(utp_buffer_bloat)
{
#if TORRENT_UTP_LOG
	lt::aux::set_utp_stream_logging(true);
#endif

	// 50 kB/s, 500 kB send buffer size. That's 10 seconds
	dsl_config cfg(50, 500000);

	std::vector<std::int64_t> cnt = utp_test(cfg);

	TEST_EQUAL(metric(cnt, "utp.utp_packet_loss"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_timeout"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_fast_retransmit"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_packet_resend"), 0);

	TEST_EQUAL(metric(cnt, "utp.utp_samples_above_target"), 429);
	TEST_EQUAL(metric(cnt, "utp.utp_samples_below_target"), 152);

	TEST_EQUAL(metric(cnt, "utp.utp_packets_in"), 633);
	TEST_EQUAL(metric(cnt, "utp.utp_payload_pkts_in"), 84);

	TEST_EQUAL(metric(cnt, "utp.utp_packets_out"), 633);

	// we don't expect any invalid packets, since we're talking to ourself
	TEST_EQUAL(metric(cnt, "utp.utp_invalid_pkts_in"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_redundant_pkts_in"), 0);
}

// low bandwidth limit, but virtually no buffer
TORRENT_TEST(utp_straw)
{
#if TORRENT_UTP_LOG
	lt::aux::set_utp_stream_logging(true);
#endif

	// 50 kB/s, 500 kB send buffer size. That's 10 seconds
	dsl_config cfg(50, 1500);

	std::vector<std::int64_t> cnt = utp_test(cfg);

	TEST_EQUAL(metric(cnt, "utp.utp_packet_loss"), 64);
	TEST_EQUAL(metric(cnt, "utp.utp_timeout"), 32);
	TEST_EQUAL(metric(cnt, "utp.utp_fast_retransmit"), 67);
	TEST_EQUAL(metric(cnt, "utp.utp_packet_resend"), 130);

	TEST_EQUAL(metric(cnt, "utp.utp_samples_above_target"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_samples_below_target"), 269);

	TEST_EQUAL(metric(cnt, "utp.utp_packets_in"), 394);
	TEST_EQUAL(metric(cnt, "utp.utp_payload_pkts_in"), 53);

	TEST_EQUAL(metric(cnt, "utp.utp_packets_out"), 531);

	// we don't expect any invalid packets, since we're talking to ourself
	TEST_EQUAL(metric(cnt, "utp.utp_invalid_pkts_in"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_redundant_pkts_in"), 0);
}

TORRENT_TEST(utp_small_kernel_send_buf)
{
#if TORRENT_UTP_LOG
	lt::aux::set_utp_stream_logging(true);
#endif

	dsl_config cfg(50000, 1000000, lt::milliseconds(10));

	std::vector<std::int64_t> cnt = utp_test(cfg, 5000);

	TEST_EQUAL(metric(cnt, "utp.utp_packet_loss"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_timeout"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_fast_retransmit"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_packet_resend"), 263);

	TEST_EQUAL(metric(cnt, "utp.utp_samples_above_target"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_samples_below_target"), 1010);

	TEST_EQUAL(metric(cnt, "utp.utp_packets_in"), 1018);
	TEST_EQUAL(metric(cnt, "utp.utp_payload_pkts_in"), 69);

	TEST_EQUAL(metric(cnt, "utp.utp_packets_out"), 1035);

	// we don't expect any invalid packets, since we're talking to ourself
	TEST_EQUAL(metric(cnt, "utp.utp_invalid_pkts_in"), 0);
	TEST_EQUAL(metric(cnt, "utp.utp_redundant_pkts_in"), 0);
}

// --- Beam: µTP-Durchsatz LEDBAT vs BBR über dasselbe WAN-Profil ---
// Schaltet den Congestion-Controller per settings_pack::utp_congestion_control um. Deterministisch,
// solo, in Sekunden. Ergebnis → C:\boost_dl\beam_throughput.txt (Test-Framework schluckt stdout).
// Beam-Testknopf (libsimulator/queue): deterministischer WAN-Verlust in Promille.
namespace sim { void set_global_loss_permille(int); }

static void run_beam_throughput(int const cc, char const* const label
	, int const loss_permille, int const latency_ms, int const queue_bytes
	, int const rate_kBps = 400)
{
	std::int64_t const torrent_size = 128 * std::int64_t(0x4000);   // huge_torrent = 2 MB

	sim::set_global_loss_permille(loss_permille);   // WAN-Loss an (0 = aus)
	dsl_config cfg(rate_kBps, queue_bytes, lt::milliseconds(latency_ms));
	sim::simulation sim{cfg};

	lt::time_point const t0 = lt::clock_type::now();
	int elapsed_ms = -1;
	int peak_rate = 0;   // höchste beobachtete download_rate (Bytes/s) = Steady-State-Proxy
	// DOWNLOAD-Test: session 0 = Downloader (saubere Fertig-Erkennung via is_seed; Fake-Disk per set_seed,
	// kein seed_mode-Read → kein Harness-Crash). CC wird per new_session auf ALLE Sessions angewandt →
	// der Seed (Sender) nutzt BBR/LEDBAT → steuert die Transferrate.
	setup_swarm(2, swarm_test::download | swarm_test::huge_torrent | swarm_test::no_auto_stop, sim
		, [&](lt::settings_pack& pack) {
			utp_only(pack);
			pack.set_int(settings_pack::utp_congestion_control, cc);
		}
		, [](lt::add_torrent_params&) {}
		, [&](lt::alert const* a, lt::session&) {
			// torrent_finished_alert feuert GENAU bei Abschluss (nicht erst am 1-s-Tick) → feine Zeit
			if (lt::alert_cast<lt::torrent_finished_alert>(a) && elapsed_ms < 0)
				elapsed_ms = int(lt::duration_cast<lt::milliseconds>(lt::clock_type::now() - t0).count());
		}
		, [&](int const ticks, lt::session& s) -> bool
		{
			auto h = s.get_torrents();
			if (!h.empty()) peak_rate = std::max(peak_rate, int(h[0].status().download_rate));
			if (elapsed_ms >= 0) return true;
			if (ticks > 600) { TEST_ERROR("beam throughput timeout"); return true; }
			return false;
		});

	sim::set_global_loss_permille(0);   // Loss wieder aus für nachfolgende Tests

	double const mb        = double(torrent_size) / (1024.0 * 1024.0);
	double const secs      = (elapsed_ms > 0) ? elapsed_ms / 1000.0 : 0.0;
	double const mbps      = (secs > 0) ? mb / secs : 0.0;
	double const link_mbps = double(rate_kBps) / 1024.0;
	double const util      = (link_mbps > 0) ? mbps / link_mbps * 100.0 : 0.0;
	std::ofstream out("C:\\boost_dl\\beam_throughput.txt", std::ios::app);
	out << "BEAM uTP [" << label << "]"
		<< " | link " << rate_kBps << " kB/s (~" << link_mbps << " MB/s)"
		<< ", RTT " << latency_ms * 2 << " ms, queue " << queue_bytes / 1024 << " kB"
		<< ", loss " << loss_permille / 10.0 << "%"
		<< " | " << mb << " MB in " << elapsed_ms << " ms"
		<< " | achieved " << mbps << " MB/s (" << util << "% of link)"
		<< " | PEAK " << peak_rate / (1024.0 * 1024.0) << " MB/s ("
		<< (rate_kBps > 0 ? double(peak_rate) / (rate_kBps * 1024.0) * 100.0 : 0.0) << "% of link)\n";
	out.close();
}

// === BBR vs LEDBAT, 2 MB, 256-KB-WAN-Puffer, RTT 120 ms ===
// Referenzmessungen (2026-06-16, peak = Steady-State-Proxy):
//   loss 0,0%:  BBR 81% peak  | LEDBAT ~88% peak  (clean → Gleichstand, beide sättigen)
//   loss 1,0%:  BBR 69% peak  | LEDBAT 16% peak   (WAN → BBR 3,6× schneller; DER Kernbeweis)
// BBR-Paar läuft schnell durch; LEDBAT-lossy kriecht (~40s sim) → optional, langsam.
TORRENT_TEST(beam_bbr_clean) { run_beam_throughput(settings_pack::utp_bbr,    "BBR    loss0%", 0,  60, 256*1024); }
TORRENT_TEST(beam_bbr_wan1)  { run_beam_throughput(settings_pack::utp_bbr,    "BBR    loss1%", 10, 60, 256*1024); }
TORRENT_TEST(beam_led_wan1)  { run_beam_throughput(settings_pack::utp_ledbat, "LEDBAT loss1%", 10, 60, 256*1024); }

// === LAN-Reproduktion: niedrige Latenz (2ms → RTT ~4ms), hohe Rate. Verdacht: STARTUP-Exit-Heuristik
// (rtt > 1,5×rtprop) trippt bei winziger Basis-RTT sofort → BBR deckelt niedrig. Erwartung wenn Bug:
// BBR-Peak << LEDBAT-Peak. (rate=5000 kB/s ~40 Mbit, queue 512 KB, kein Loss.) ===
// TORRENT_TEST(beam_bbr_lan)  { run_beam_throughput(settings_pack::utp_bbr,    "BBR    LAN clean", 0, 2, 512*1024, 5000); }
// TORRENT_TEST(beam_led_lan)  { run_beam_throughput(settings_pack::utp_ledbat, "LEDBAT LAN clean", 0, 2, 512*1024, 5000); }
// Gegencheck: gleiche schnelle Strecke mit 1% Loss → hier MUSS BBR LEDBAT schlagen, sonst wäre BBR kaputt.
TORRENT_TEST(beam_bbr_lanL) { run_beam_throughput(settings_pack::utp_bbr,    "BBR    LAN 1%",    10, 2, 512*1024, 5000); }
TORRENT_TEST(beam_led_lanL) { run_beam_throughput(settings_pack::utp_ledbat, "LEDBAT LAN 1%",    10, 2, 512*1024, 5000); }

