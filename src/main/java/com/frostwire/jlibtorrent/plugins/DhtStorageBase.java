package com.frostwire.jlibtorrent.plugins;

import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.swig.*;

import java.util.*;

/**
 * @author gubatron
 * @author aldenml
 */
public class DhtStorageBase implements DhtStorage {

    private final Sha1Hash id;
    private final DhtSettings settings;
    private final Counters counters;

    private final HashMap<Sha1Hash, TorrentEntry> torrents;
    private final HashMap<Sha1Hash, DhtImmutableItem> immutables;
    private final HashMap<Sha1Hash, DhtMutableItem> mutables;

    public DhtStorageBase(Sha1Hash id, DhtSettings settings) {
        this.id = id;
        this.settings = settings;
        this.counters = new Counters();

        this.torrents = new HashMap<Sha1Hash, TorrentEntry>();
        this.immutables = new HashMap<Sha1Hash, DhtImmutableItem>();
        this.mutables = new HashMap<Sha1Hash, DhtMutableItem>();
    }

    @Override
    public boolean getPeers(Sha1Hash infoHash, boolean noseed, boolean scrape, entry peers) {
        TorrentEntry v = torrents.get(infoHash);

        if (v == null) {
            return false;
        }

        if (!v.name.isEmpty()) {
            peers.set("n", v.name);
        }

        if (scrape) {
            bloom_filter_256 downloaders = new bloom_filter_256();
            bloom_filter_256 seeds = new bloom_filter_256();

            for (PeerEntry peer : v.peers) {
                sha1_hash iphash = new sha1_hash();
                libtorrent.sha1_hash_address(peer.addr.address(), iphash);
                if (peer.seed) {
                    seeds.set(iphash);
                } else {
                    downloaders.set(iphash);
                }
            }

            peers.set("BFpe", downloaders.to_bytes());
            peers.set("BFsd", seeds.to_bytes());
        } else {
            int num = Math.min(v.peers.size(), settings.maxPeersReply());
            Iterator<PeerEntry> iter = v.peers.iterator();
            entry_list pe = peers.get("values").list();
            byte_vector endpoint = new byte_vector();

            for (int t = 0, m = 0; m < num && iter.hasNext(); ++t) {
                PeerEntry e = iter.next();
                if ((Math.random() / (Integer.MAX_VALUE + 1.f)) * (num - t) >= num - m) continue;
                if (noseed && e.seed) continue;
                endpoint.resize(18);
                int n = libtorrent.write_tcp_endpoint(e.addr, endpoint);
                endpoint.resize(n);
                pe.push_back(new entry(endpoint));

                ++m;
            }
        }

        return true;
    }

    @Override
    public void announcePeer(Sha1Hash infoHash, TcpEndpoint endp, String name, boolean seed) {
        TorrentEntry v = torrents.get(infoHash);
        if (v == null) {
            // we don't have this torrent, add it
            // do we need to remove another one first?
            if (!torrents.isEmpty() && torrents.size() >= settings.maxTorrents()) {
                // we need to remove some. Remove the ones with the
                // fewest peers
                int num_peers = Integer.MAX_VALUE;
                Sha1Hash candidateKey = null;
                for (Map.Entry<Sha1Hash, TorrentEntry> kv : torrents.entrySet()) {

                    if (kv.getValue().peers.size() > num_peers) {
                        continue;
                    }
                    if (infoHash.equals(kv.getKey())) {
                        continue;
                    }
                    num_peers = kv.getValue().peers.size();
                    candidateKey = kv.getKey();
                }
                torrents.remove(candidateKey);
                counters.peers -= num_peers;
                counters.torrents -= 1;
            }
            counters.torrents += 1;
            v = new TorrentEntry();
            torrents.put(infoHash, v);
        }

        // the peer announces a torrent name, and we don't have a name
        // for this torrent. Store it.
        if (!name.isEmpty() && v.name.isEmpty()) {
            String tname = name;
            if (tname.length() > 100) {
                tname = tname.substring(0, 99);
            }
            v.name = tname;
        }

        PeerEntry peer = new PeerEntry();
        peer.addr = endp.swig();
        peer.added = System.currentTimeMillis();
        peer.seed = seed;

        if (v.peers.contains(peer)) {
            v.peers.remove(peer);
            counters.peers -= 1;
        } else if (v.peers.size() >= settings.maxPeers()) {
            // when we're at capacity, there's a 50/50 chance of dropping the
            // announcing peer or an existing peer
            if (Math.random() > 0.5) {
                return;
            }
            PeerEntry t = v.peers.lower(peer);
            if (t == null) {
                t = v.peers.first();
            }
            v.peers.remove(t);
            counters.peers -= 1;
        }
        v.peers.add(peer);
        counters.peers += 1;
    }

    @Override
    public boolean getImmutableItem(Sha1Hash target, entry item) {
        DhtImmutableItem i = immutables.get(target);
        if (i == null) {
            return false;
        }

        item.set("v", entry.bdecode(Vectors.bytes2byte_vector(i.value)));
        return true;
    }

    @Override
    public void putImmutableItem(Sha1Hash target, byte[] buf, Address addr) {
        DhtImmutableItem i = immutables.get(target);
        if (i == null) {
            // make sure we don't add too many items
            if (immutables.size() >= settings.maxDhtItems()) {
                // delete the least important one (i.e. the one
                // the fewest peers are announcing, and farthest
                // from our node ID)
                Map.Entry<Sha1Hash, DhtImmutableItem> j = Collections.min(immutables.entrySet(), new ImmutableItemComparator(id));
                immutables.remove(j.getKey());
                counters.immutable_data -= 1;
            }
            i = new DhtImmutableItem();
            i.value = buf;
            i.ips = new bloom_filter_128();
            immutables.put(target, i);
            counters.immutable_data += 1;
        }

        touchItem(i, addr.swig());
    }

    @Override
    public long getMutableItemSeq(Sha1Hash target) {
        DhtMutableItem i = mutables.get(target);
        return i != null ? i.seq : -1;
    }

    @Override
    public boolean getMutableItem(Sha1Hash target, long seq, boolean forceFill, entry item) {
        return false;
    }

    @Override
    public void putMutableItem(Sha1Hash target, byte[] buf, byte[] sig, long seq, byte[] pk, byte[] salt, Address addr) {

    }

    @Override
    public void tick() {

    }

    @Override
    public Counters counters() {
        return counters;
    }

    private static void touchItem(DhtImmutableItem f, address address) {
        f.last_seen = System.currentTimeMillis();

        // maybe increase num_announcers if we haven't seen this IP before
        sha1_hash iphash = new sha1_hash();
        libtorrent.sha1_hash_address(address, iphash);
        if (!f.ips.find(iphash)) {
            f.ips.set(iphash);
            f.num_announcers++;
        }
    }

    private static final class PeerEntry {

        public long added;
        public tcp_endpoint addr;
        public boolean seed;

        public static final Comparator<PeerEntry> COMPARATOR = new Comparator<PeerEntry>() {
            @Override
            public int compare(PeerEntry o1, PeerEntry o2) {
                tcp_endpoint a1 = o1.addr;
                tcp_endpoint a2 = o2.addr;

                int r = address.compare(a1.address(), a2.address());

                return r == 0 ? Integer.compare(a1.port(), a2.port()) : r;
            }
        };
    }

    private static final class TorrentEntry {

        public TorrentEntry() {
            this.name = "";
            this.peers = new TreeSet<PeerEntry>(PeerEntry.COMPARATOR);
        }

        public String name;
        public TreeSet<PeerEntry> peers;
    }

    private static class DhtImmutableItem {
        // malloced space for the actual value
        public byte[] value;
        // this counts the number of IPs we have seen
        // announcing this item, this is used to determine
        // popularity if we reach the limit of items to store
        public bloom_filter_128 ips;
        // the last time we heard about this
        public long last_seen;
        // number of IPs in the bloom filter
        public int num_announcers;
    }

    private static final class DhtMutableItem extends DhtImmutableItem {
        public byte[] sig = new byte[64];
        public long seq;
        public byte[] key = new byte[32];
        public byte[] salt;
    }

    private static final class ImmutableItemComparator implements Comparator<Map.Entry<Sha1Hash, DhtImmutableItem>> {

        private final Sha1Hash ourId;

        public ImmutableItemComparator(Sha1Hash ourId) {
            this.ourId = ourId;
        }

        @Override
        public int compare(Map.Entry<Sha1Hash, DhtImmutableItem> lhs, Map.Entry<Sha1Hash, DhtImmutableItem> rhs) {
            int l_distance = libtorrent.dht_distance_exp(lhs.getKey().swig(), ourId.swig());
            int r_distance = libtorrent.dht_distance_exp(rhs.getKey().swig(), ourId.swig());

            // this is a score taking the popularity (number of announcers) and the
            // fit, in terms of distance from ideal storing node, into account.
            // each additional 5 announcers is worth one extra bit in the distance.
            // that is, an item with 10 announcers is allowed to be twice as far
            // from another item with 5 announcers, from our node ID. Twice as far
            // because it gets one more bit.
            int a = lhs.getValue().num_announcers / 5 - l_distance;
            int b = rhs.getValue().num_announcers / 5 - r_distance;
            return Integer.compare(a, b);
        }
    }
}