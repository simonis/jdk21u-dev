/*
 * Copyright (c) 2000, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.jndi.dns;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.SecureRandom;
import javax.naming.CommunicationException;
import javax.naming.ConfigurationException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.OperationNotSupportedException;
import javax.naming.ServiceUnavailableException;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

import jdk.internal.ref.CleanerFactory;
import sun.security.jca.JCAUtil;

// Some of this code began life as part of sun.javaos.net.DnsClient
// originally by sritchie@eng 1/96.  It was first hacked up for JNDI
// use by caveh@eng 6/97.


/**
 * The DnsClient class performs DNS client operations in support of DnsContext.
 *
 */

public class DnsClient {

    // DNS packet header field offsets
    private static final int IDENT_OFFSET = 0;
    private static final int FLAGS_OFFSET = 2;
    private static final int NUMQ_OFFSET  = 4;
    private static final int NUMANS_OFFSET = 6;
    private static final int NUMAUTH_OFFSET = 8;
    private static final int NUMADD_OFFSET = 10;
    private static final int DNS_HDR_SIZE = 12;

    // DNS response codes
    private static final int NO_ERROR       = 0;
    private static final int FORMAT_ERROR   = 1;
    private static final int SERVER_FAILURE = 2;
    private static final int NAME_ERROR     = 3;
    private static final int NOT_IMPL       = 4;
    private static final int REFUSED        = 5;

    private static final String[] rcodeDescription = {
        "No error",
        "DNS format error",
        "DNS server failure",
        "DNS name not found",
        "DNS operation not supported",
        "DNS service refused"
    };

    private static final int DEFAULT_PORT = 53;
    private static final int TRANSACTION_ID_BOUND = 0x10000;
    private static final int MIN_TIMEOUT = 0; // msec after which there are no retries.
    private static final SecureRandom random = JCAUtil.getSecureRandom();
    private InetAddress[] servers;
    private int[] serverPorts;
    private int timeout;                // initial timeout on UDP and TCP queries in ms
    private int retries;                // number of UDP retries

    private final ReentrantLock udpChannelLock = new ReentrantLock();

    private final Selector udpChannelSelector;
    private final Cleaner.Cleanable selectorCleanup;

    private static final DNSDatagramChannelFactory factory =
            new DNSDatagramChannelFactory(random);

    // Requests sent
    private Map<Integer, ResourceRecord> reqs;

    // Responses received
    private Map<Integer, byte[]> resps;

    //-------------------------------------------------------------------------

    /*
     * Each server is of the form "server[:port]".  IPv6 literal host names
     * include delimiting brackets.
     * "timeout" is the initial timeout interval (in ms) for queries,
     * and "retries" gives the number of retries per server.
     */
    public DnsClient(String[] servers, int timeout, int retries)
            throws NamingException {
        this.timeout = timeout;
        this.retries = retries;
        this.servers = new InetAddress[servers.length];
        serverPorts = new int[servers.length];

        for (int i = 0; i < servers.length; i++) {

            // Is optional port given?
            int colon = servers[i].indexOf(':',
                                           servers[i].indexOf(']') + 1);

            serverPorts[i] = (colon < 0)
                ? DEFAULT_PORT
                : Integer.parseInt(servers[i].substring(colon + 1));
            String server = (colon < 0)
                ? servers[i]
                : servers[i].substring(0, colon);
            try {
                this.servers[i] = InetAddress.getByName(server);
            } catch (java.net.UnknownHostException e) {
                NamingException ne = new ConfigurationException(
                        "Unknown DNS server: " + server);
                ne.setRootCause(e);
                throw ne;
            }
        }

        try {
            udpChannelSelector = Selector.open();
        } catch (IOException e) {
            NamingException ne = new ConfigurationException(
                    "Channel selector configuration error");
            ne.setRootCause(e);
            throw ne;
        }
        // register a cleaning action to close the Selector
        // when this DNS client becomes phantom reachable
        Selector sel = udpChannelSelector;
        selectorCleanup = CleanerFactory.cleaner().register(this, () -> {
            try {
                sel.close();
            } catch (IOException ignore) {
            }
        });

        reqs = Collections.synchronizedMap(
            new HashMap<Integer, ResourceRecord>());
        resps = Collections.synchronizedMap(new HashMap<Integer, byte[]>());
    }

    DatagramChannel getDatagramChannel() throws NamingException {
        try {
            return factory.open();
        } catch (IOException e) {
            NamingException ne = new ConfigurationException();
            ne.setRootCause(e);
            throw ne;
        }
    }

    // A lock to access the request and response queues in tandem.
    private Object queuesLock = new Object();

    public void close() {
        selectorCleanup.clean();
        synchronized (queuesLock) {
            reqs.clear();
            resps.clear();
        }
    }

    /*
     * If recursion is true, recursion is requested on the query.
     * If auth is true, only authoritative responses are accepted; other
     * responses throw NameNotFoundException.
     */
    ResourceRecords query(DnsName fqdn, int qclass, int qtype,
                          boolean recursion, boolean auth)
            throws NamingException {

        int xid;
        Packet pkt;
        ResourceRecord collision;

        do {
            // Generate a random transaction ID
            xid = random.nextInt(TRANSACTION_ID_BOUND);
            pkt = makeQueryPacket(fqdn, xid, qclass, qtype, recursion);

            // enqueue the outstanding request
            collision = reqs.putIfAbsent(xid, new ResourceRecord(pkt.getData(),
                pkt.length(), Header.HEADER_SIZE, true, false));

        } while (collision != null);

        Exception caughtException = null;
        boolean[] doNotRetry = new boolean[servers.length];
        // Holder for unfulfilled timeouts left for each server
        AtomicLong[] unfulfilledUdpTimeouts = IntStream.range(0, servers.length)
                .mapToObj(AtomicLong::new)
                .toArray(AtomicLong[]::new);
        try {
            //
            // The UDP retry strategy is to try the 1st server, and then
            // each server in order. If no answer, double the timeout
            // and try each server again.
            //
            for (int retry = 0; retry <= retries; retry++) {
                boolean isLastRetry = retry == retries;
                // Try each name server.
                for (int i = 0; i < servers.length; i++) {
                    if (doNotRetry[i]) {
                        continue;
                    }
                    // unfulfilledServerTimeout is always >= 0
                    AtomicLong unfulfilledServerTimeout = unfulfilledUdpTimeouts[i];
                    if (isLastRetry && unfulfilledServerTimeout.get() == 0) {
                        continue;
                    }

                    // send the request packet and wait for a response.
                    try {
                        if (debug) {
                            dprint("SEND ID (" + (retry + 1) + "): " + xid);
                        }

                        byte[] msg = doUdpQuery(pkt, servers[i], serverPorts[i],
                                retry, xid, unfulfilledServerTimeout, isLastRetry);
                        assert msg != null;
                        Header hdr = new Header(msg, msg.length);

                        if (auth && !hdr.authoritative) {
                            caughtException = new NameNotFoundException(
                                    "DNS response not authoritative");
                            doNotRetry[i] = true;
                            continue;
                        }
                        if (hdr.truncated) {  // message is truncated -- try TCP

                            // Try each server, starting with the one that just
                            // provided the truncated message.
                            long retryTimeout = Math.clamp(
                                    timeout * (1L << (isLastRetry
                                            ? retry - 1
                                            : retry)),
                                    0L, Integer.MAX_VALUE);
                            ;
                            for (int j = 0; j < servers.length; j++) {
                                int ij = (i + j) % servers.length;
                                if (doNotRetry[ij]) {
                                    continue;
                                }
                                try {
                                    Tcp tcp =
                                        new Tcp(servers[ij], serverPorts[ij], retryTimeout);
                                    byte[] msg2;
                                    try {
                                        msg2 = doTcpQuery(tcp, pkt);
                                    } finally {
                                        tcp.close();
                                    }
                                    Header hdr2 = new Header(msg2, msg2.length);
                                    if (hdr2.query) {
                                        throw new CommunicationException(
                                            "DNS error: expecting response");
                                    }
                                    checkResponseCode(hdr2);

                                    if (!auth || hdr2.authoritative) {
                                        // Got a valid response
                                        hdr = hdr2;
                                        msg = msg2;
                                        break;
                                    } else {
                                        doNotRetry[ij] = true;
                                    }
                                } catch (Exception e) {
                                    // Try next server, or use UDP response
                                }
                            } // servers
                        }
                        return new ResourceRecords(msg, msg.length, hdr, false);
                    } catch (UncheckedIOException | PortUnreachableException ex) {
                        // DatagramSocket.connect in doUdpQuery can throw UncheckedIOException
                        // DatagramSocket.send in doUdpQuery can throw PortUnreachableException
                        if (debug) {
                            dprint("Caught Exception:" + ex);
                        }
                        if (caughtException == null || servers.length == 1) {
                            // If there are several servers we continue trying with other
                            // servers, otherwise this exception will be reported
                            caughtException = ex;
                        } else {
                            // Best reporting effort
                            caughtException.addSuppressed(ex);
                        }
                        doNotRetry[i] = true;
                    } catch (IOException e) {
                        if (debug) {
                            dprint("Caught IOException:" + e);
                        }
                        if (caughtException instanceof CommunicationException ce) {
                            e.addSuppressed(ce);
                            caughtException = e;
                        } else if (caughtException == null) {
                            caughtException = e;
                        }
                    } catch (ClosedSelectorException e) {
                        // ClosedSelectorException is thrown by blockingReceive if
                        // the datagram channel selector associated with DNS client
                        // is unexpectedly closed
                        var ce = new CommunicationException("DNS client closed");
                        ce.setRootCause(e);
                        throw ce;
                    } catch (NameNotFoundException e) {
                        // This is authoritative, so return immediately
                        throw e;
                    } catch (CommunicationException e) {
                        if (caughtException == null) {
                            caughtException = e;
                        }
                    } catch (NamingException e) {
                        if (caughtException == null || servers.length == 1) {
                            // If there are several servers we continue trying with other
                            // servers, otherwise this exception will be reported
                            caughtException = e;
                        } else {
                            // Best reporting effort
                            caughtException.addSuppressed(e);
                        }
                        doNotRetry[i] = true;
                    }
                } // servers
            } // retries

        } finally {
            reqs.remove(xid); // cleanup
        }

        if (caughtException instanceof NamingException ne) {
            throw ne;
        }
        // A network timeout or other error occurred.
        NamingException ne = new CommunicationException("DNS error");
        ne.setRootCause(caughtException);
        throw ne;
    }

    ResourceRecords queryZone(DnsName zone, int qclass, boolean recursion)
            throws NamingException {

        int xid = random.nextInt(TRANSACTION_ID_BOUND);

        Packet pkt = makeQueryPacket(zone, xid, qclass,
                                     ResourceRecord.QTYPE_AXFR, recursion);
        Exception caughtException = null;

        // Try each name server.
        for (int i = 0; i < servers.length; i++) {
            try {
                Tcp tcp = new Tcp(servers[i], serverPorts[i], timeout);
                byte[] msg;
                try {
                    msg = doTcpQuery(tcp, pkt);
                    Header hdr = new Header(msg, msg.length);
                    // Check only rcode as per
                    // draft-ietf-dnsext-axfr-clarify-04
                    checkResponseCode(hdr);
                    ResourceRecords rrs =
                        new ResourceRecords(msg, msg.length, hdr, true);
                    if (rrs.getFirstAnsType() != ResourceRecord.TYPE_SOA) {
                        throw new CommunicationException(
                                "DNS error: zone xfer doesn't begin with SOA");
                    }

                    if (rrs.answer.size() == 1 ||
                            rrs.getLastAnsType() != ResourceRecord.TYPE_SOA) {
                        // The response is split into multiple DNS messages.
                        do {
                            msg = continueTcpQuery(tcp);
                            if (msg == null) {
                                throw new CommunicationException(
                                        "DNS error: incomplete zone transfer");
                            }
                            hdr = new Header(msg, msg.length);
                            checkResponseCode(hdr);
                            rrs.add(msg, msg.length, hdr);
                        } while (rrs.getLastAnsType() !=
                                 ResourceRecord.TYPE_SOA);
                    }

                    // Delete the duplicate SOA record.
                    rrs.answer.removeElementAt(rrs.answer.size() - 1);
                    return rrs;

                } finally {
                    tcp.close();
                }

            } catch (IOException e) {
                caughtException = e;
            } catch (NameNotFoundException e) {
                throw e;
            } catch (NamingException e) {
                caughtException = e;
            }
        }
        if (caughtException instanceof NamingException) {
            throw (NamingException) caughtException;
        }
        NamingException ne = new CommunicationException(
                "DNS error during zone transfer");
        ne.setRootCause(caughtException);
        throw ne;
    }


    /**
     * Tries to retrieve a UDP packet matching the given xid
     * received within the timeout.
     * If a packet with different xid is received, the received packet
     * is enqueued with the corresponding xid in 'resps'.
     */
    private byte[] doUdpQuery(Packet pkt, InetAddress server,
                              int port, int retry, int xid,
                              AtomicLong unfulfilledTimeout,
                              boolean unfulfilledOnly)
            throws IOException, NamingException {

        udpChannelLock.lock();


        // use 1L below to ensure conversion to long and avoid potential
        // integer overflow (timeout is an int).
        // no point in supporting timeout > Integer.MAX_VALUE, clamp if needed
        // timeout remaining after successive 'blockingReceive()'.
        long thisIterationTimeout = unfulfilledOnly
                ? 0L
                : Math.clamp(timeout * (1L << retry), 0L, Integer.MAX_VALUE);

        // Compensate with server's positive unfulfilled timeout.
        // Calling method never supplies zero 'unfulfilledTimeout' when
        // 'unfulfilledOnly' is 'true', therefore 'thisIterationTimeout'
        // will always be a positive number, ie infinite timeout
        // is not possible.
        thisIterationTimeout += unfulfilledTimeout.get();

        // Track left timeout for the current retry
        long timeoutLeft = thisIterationTimeout;
        long start = 0;
        try {
            try (DatagramChannel udpChannel = getDatagramChannel()) {
                ByteBuffer opkt = ByteBuffer.wrap(pkt.getData(), 0, pkt.length());
                byte[] data = new byte[8000];
                ByteBuffer ipkt = ByteBuffer.wrap(data);
                // Packets may only be sent to or received from this server address
                InetSocketAddress target = new InetSocketAddress(server, port);
                udpChannel.connect(target);
                udpChannel.write(opkt);

                int cnt = 0;
                boolean gotData = false;
                start = System.nanoTime();
                do {
                    // prepare for retry
                    if (gotData) {
                        Arrays.fill(data, 0, ipkt.position(), (byte) 0);
                        ipkt.clear();
                    }
                    if (debug) {
                        cnt++;
                        dprint("Trying RECEIVE(" +
                                cnt + ") retry(" + (retry + 1) +
                                ") for:" + xid + "    sock-timeout:" +
                                timeoutLeft + " ms.");
                    }
                    gotData = blockingReceive(udpChannel, target, ipkt, timeoutLeft);
                    assert gotData || ipkt.position() == 0;
                    if (gotData && isMatchResponse(data, xid)) {
                        return data;
                    } else if (resps.size() > 0) {
                        // If the matching response is not found, check if
                        // the response was enqueued by some other thread,
                        // if not continue
                        byte[] cachedMsg = lookupResponse(xid);
                        if (cachedMsg != null) { // found in cache
                            return cachedMsg;
                        }
                    }
                    long elapsedMillis = TimeUnit.NANOSECONDS
                                                 .toMillis(System.nanoTime() - start);
                    timeoutLeft = thisIterationTimeout - elapsedMillis;
                } while (timeoutLeft > MIN_TIMEOUT);
                // no matching packets received within the timeout
                throw new SocketTimeoutException();
            }
        } finally {
            long carryoverTimeout = thisIterationTimeout -
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            unfulfilledTimeout.set(Math.max(0, carryoverTimeout));
            udpChannelLock.unlock();
        }
    }

    boolean blockingReceive(DatagramChannel dc, InetSocketAddress target,
                            ByteBuffer buffer, long timeout) throws IOException {
        boolean dataReceived = false;
        // The provided datagram channel will be used by the caller only to receive data after
        // it is put to non-blocking mode
        dc.configureBlocking(false);
        var selectionKey = dc.register(udpChannelSelector, SelectionKey.OP_READ);
        try {
            udpChannelSelector.select(timeout);
            var keys = udpChannelSelector.selectedKeys();
            if (keys.contains(selectionKey) && selectionKey.isReadable()) {
                int before = buffer.position();
                var senderAddress = dc.receive(buffer);
                // Empty packets are ignored
                dataReceived = target.equals(senderAddress) && buffer.position() > before;
            }
            // Avoid contention with Selector.close() if called by a clean-up thread
            synchronized (keys) {
                keys.clear();
            }
        } finally {
            selectionKey.cancel();
            // Flush the canceled key out of the selected key set
            udpChannelSelector.selectNow();
        }
        return dataReceived;
    }

    /*
     * Sends a TCP query, and returns the first DNS message in the response.
     */
    private byte[] doTcpQuery(Tcp tcp, Packet pkt) throws IOException {

        int len = pkt.length();
        // Send 2-byte message length, then send message.
        tcp.out.write(len >> 8);
        tcp.out.write(len);
        tcp.out.write(pkt.getData(), 0, len);
        tcp.out.flush();

        byte[] msg = continueTcpQuery(tcp);
        if (msg == null) {
            throw new IOException("DNS error: no response");
        }
        return msg;
    }

    /*
     * Returns the next DNS message from the TCP socket, or null on EOF.
     */
    private byte[] continueTcpQuery(Tcp tcp) throws IOException {

        int lenHi = tcp.read();      // high-order byte of response length
        if (lenHi == -1) {
            return null;        // EOF
        }
        int lenLo = tcp.read();      // low-order byte of response length
        if (lenLo == -1) {
            throw new IOException("Corrupted DNS response: bad length");
        }
        int len = (lenHi << 8) | lenLo;
        byte[] msg = new byte[len];
        int pos = 0;                    // next unfilled position in msg
        while (len > 0) {
            int n = tcp.read(msg, pos, len);
            if (n == -1) {
                throw new IOException(
                        "Corrupted DNS response: too little data");
            }
            len -= n;
            pos += n;
        }
        return msg;
    }

    private Packet makeQueryPacket(DnsName fqdn, int xid,
                                   int qclass, int qtype, boolean recursion) {
        int qnameLen = fqdn.getOctets();
        int pktLen = DNS_HDR_SIZE + qnameLen + 4;
        Packet pkt = new Packet(pktLen);

        short flags = recursion ? Header.RD_BIT : 0;

        pkt.putShort(xid, IDENT_OFFSET);
        pkt.putShort(flags, FLAGS_OFFSET);
        pkt.putShort(1, NUMQ_OFFSET);
        pkt.putShort(0, NUMANS_OFFSET);
        pkt.putInt(0, NUMAUTH_OFFSET);

        makeQueryName(fqdn, pkt, DNS_HDR_SIZE);
        pkt.putShort(qtype, DNS_HDR_SIZE + qnameLen);
        pkt.putShort(qclass, DNS_HDR_SIZE + qnameLen + 2);

        return pkt;
    }

    // Builds a query name in pkt according to the RFC spec.
    private void makeQueryName(DnsName fqdn, Packet pkt, int off) {

        // Loop through labels, least-significant first.
        for (int i = fqdn.size() - 1; i >= 0; i--) {
            String label = fqdn.get(i);
            int len = label.length();

            pkt.putByte(len, off++);
            for (int j = 0; j < len; j++) {
                pkt.putByte(label.charAt(j), off++);
            }
        }
        if (!fqdn.hasRootLabel()) {
            pkt.putByte(0, off);
        }
    }

    //-------------------------------------------------------------------------

    private byte[] lookupResponse(Integer xid) throws NamingException {
        //
        // Check the queued responses: some other thread in between
        // received the response for this request.
        //
        if (debug) {
            dprint("LOOKUP for: " + xid +
                "\tResponse Q:" + resps);
        }
        byte[] pkt;
        if ((pkt = resps.get(xid)) != null) {
            checkResponseCode(new Header(pkt, pkt.length));
            synchronized (queuesLock) {
                resps.remove(xid);
                reqs.remove(xid);
            }

            if (debug) {
                dprint("FOUND (" + Thread.currentThread() +
                    ") for:" + xid);
            }
        }
        return pkt;
    }

    /*
     * Checks the header of an incoming DNS response.
     * Returns true if it matches the given xid and throws a naming
     * exception, if appropriate, based on the response code.
     *
     * Also checks that the domain name, type and class in the response
     * match those in the original query.
     */
    private boolean isMatchResponse(byte[] pkt, int xid)
                throws NamingException {

        Header hdr = new Header(pkt, pkt.length);
        if (hdr.query) {
            throw new CommunicationException("DNS error: expecting response");
        }

        if (!reqs.containsKey(xid)) { // already received, ignore the response
            return false;
        }

        // common case- the request sent matches the subsequent response read
        if (hdr.xid == xid) {
            if (debug) {
                dprint("XID MATCH:" + xid);
            }
            checkResponseCode(hdr);
            if (!hdr.query && hdr.numQuestions == 1) {

                ResourceRecord rr = new ResourceRecord(pkt, pkt.length,
                    Header.HEADER_SIZE, true, false);

                // Retrieve the original query
                ResourceRecord query = reqs.get(xid);
                int qtype = query.getType();
                int qclass = query.getRrclass();
                DnsName qname = query.getName();

                // Check that the type/class/name in the query section of the
                // response match those in the original query
                if ((qtype == ResourceRecord.QTYPE_STAR ||
                    qtype == rr.getType()) &&
                    (qclass == ResourceRecord.QCLASS_STAR ||
                    qclass == rr.getRrclass()) &&
                    qname.equals(rr.getName())) {

                    if (debug) {
                        dprint("MATCH NAME:" + qname + " QTYPE:" + qtype +
                            " QCLASS:" + qclass);
                    }

                    // Remove the response for the xid if received by some other
                    // thread.
                    synchronized (queuesLock) {
                        resps.remove(xid);
                        reqs.remove(xid);
                    }
                    return true;

                } else {
                    if (debug) {
                        dprint("NO-MATCH NAME:" + qname + " QTYPE:" + qtype +
                            " QCLASS:" + qclass);
                    }
                }
            }
            return false;
        }

        //
        // xid mismatch: enqueue the response, it may belong to some other
        // thread that has not yet had a chance to read its response.
        // enqueue only the first response, responses for retries are ignored.
        //
        synchronized (queuesLock) {
            if (reqs.containsKey(hdr.xid)) { // enqueue only the first response
                resps.put(hdr.xid, pkt.clone());
            }
        }

        if (debug) {
            dprint("NO-MATCH SEND ID:" +
                                xid + " RECVD ID:" + hdr.xid +
                                "    Response Q:" + resps +
                                "    Reqs size:" + reqs.size());
        }
        return false;
    }

    /*
     * Throws an exception if appropriate for the response code of a
     * given header.
     */
    private void checkResponseCode(Header hdr) throws NamingException {

        int rcode = hdr.rcode;
        if (rcode == NO_ERROR) {
            return;
        }
        String msg = (rcode < rcodeDescription.length)
            ? rcodeDescription[rcode]
            : "DNS error";
        msg += " [response code " + rcode + "]";

        switch (rcode) {
        case SERVER_FAILURE:
            throw new ServiceUnavailableException(msg);
        case NAME_ERROR:
            throw new NameNotFoundException(msg);
        case NOT_IMPL:
        case REFUSED:
            throw new OperationNotSupportedException(msg);
        case FORMAT_ERROR:
        default:
            throw new NamingException(msg);
        }
    }

    //-------------------------------------------------------------------------

    private static final boolean debug = false;

    private static void dprint(String mess) {
        if (debug) {
            System.err.println("DNS: " + mess);
        }
    }

}

class Tcp {

    private final Socket sock;
    private final java.io.InputStream in;
    final java.io.OutputStream out;
    private long timeoutLeft;

    Tcp(InetAddress server, int port, long timeout) throws IOException {
        sock = new Socket();
        try {
            long start = System.nanoTime();
            // It is safe to cast to int since the value is
            // clamped by the caller
            int intTimeout = (int) timeout;
            sock.connect(new InetSocketAddress(server, port), intTimeout);
            timeoutLeft = Duration.ofMillis(timeout)
                    .minus(Duration.ofNanos((System.nanoTime() - start)))
                    .toMillis();
            if (timeoutLeft <= 0)
                throw new SocketTimeoutException();

            sock.setTcpNoDelay(true);
            out = new java.io.BufferedOutputStream(sock.getOutputStream());
            in = new java.io.BufferedInputStream(sock.getInputStream());
        } catch (Exception e) {
            try {
                sock.close();
            } catch (IOException ex) {
                e.addSuppressed(ex);
            }
            throw e;
        }
    }

    void close() throws IOException {
        sock.close();
    }

    private interface SocketReadOp {
        int read() throws IOException;
    }

    private int readWithTimeout(SocketReadOp reader) throws IOException {
        if (timeoutLeft <= 0)
            throw new SocketTimeoutException();
        // It is safe to cast to int since the value is clamped
        int intTimeout = (int) timeoutLeft;
        sock.setSoTimeout(intTimeout);
        long start = System.nanoTime();
        try {
            return reader.read();
        }
        finally {
            timeoutLeft -= TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - start);
        }
    }

    int read() throws IOException {
        return readWithTimeout(() -> in.read());
    }

    int read(byte b[], int off, int len) throws IOException {
        return readWithTimeout(() -> in.read(b, off, len));
    }
}

/*
 * javaos emulation -cj
 */
class Packet {
        byte buf[];

        Packet(int len) {
                buf = new byte[len];
        }

        Packet(byte data[], int len) {
                buf = new byte[len];
                System.arraycopy(data, 0, buf, 0, len);
        }

        void putInt(int x, int off) {
                buf[off + 0] = (byte)(x >> 24);
                buf[off + 1] = (byte)(x >> 16);
                buf[off + 2] = (byte)(x >> 8);
                buf[off + 3] = (byte)x;
        }

        void putShort(int x, int off) {
                buf[off + 0] = (byte)(x >> 8);
                buf[off + 1] = (byte)x;
        }

        void putByte(int x, int off) {
                buf[off] = (byte)x;
        }

        void putBytes(byte src[], int src_offset, int dst_offset, int len) {
                System.arraycopy(src, src_offset, buf, dst_offset, len);
        }

        int length() {
                return buf.length;
        }

        byte[] getData() {
                return buf;
        }
}
