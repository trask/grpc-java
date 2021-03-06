/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ListenableFuture;

import io.grpc.EquivalentAddressGroup;
import io.grpc.IntegerMarshaller;
import io.grpc.LoadBalancer;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StringMarshaller;
import io.grpc.internal.TestUtils.MockClientTransportInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.SocketAddress;
import java.util.Arrays;
import java.util.LinkedList;

/**
 * Unit tests for {@link TransportSet}.
 *
 * <p>It only tests the logic that is not covered by {@link ManagedChannelImplTransportManagerTest}.
 */
@RunWith(JUnit4.class)
public class TransportSetTest {

  private static final String authority = "fakeauthority";

  private FakeClock fakeClock;

  @Mock private LoadBalancer<ClientTransport> mockLoadBalancer;
  @Mock private BackoffPolicy mockBackoffPolicy1;
  @Mock private BackoffPolicy mockBackoffPolicy2;
  @Mock private BackoffPolicy mockBackoffPolicy3;
  @Mock private BackoffPolicy.Provider mockBackoffPolicyProvider;
  @Mock private ClientTransportFactory mockTransportFactory;
  @Mock private TransportSet.Callback mockTransportSetCallback;
  @Mock private ClientStreamListener mockStreamListener;

  private final MethodDescriptor<String, Integer> method = MethodDescriptor.create(
      MethodDescriptor.MethodType.UNKNOWN, "/service/method",
      new StringMarshaller(), new IntegerMarshaller());
  private final Metadata headers = new Metadata();

  private TransportSet transportSet;
  private EquivalentAddressGroup addressGroup;
  private LinkedList<MockClientTransportInfo> transports;

  @Before public void setUp() {
    MockitoAnnotations.initMocks(this);
    fakeClock = new FakeClock();

    when(mockBackoffPolicyProvider.get())
        .thenReturn(mockBackoffPolicy1, mockBackoffPolicy2, mockBackoffPolicy3);
    when(mockBackoffPolicy1.nextBackoffMillis()).thenReturn(10L, 100L);
    when(mockBackoffPolicy2.nextBackoffMillis()).thenReturn(10L, 100L);
    when(mockBackoffPolicy3.nextBackoffMillis()).thenReturn(10L, 100L);
    transports = TestUtils.captureTransports(mockTransportFactory);
  }

  @Test public void singleAddressBackoff() {
    SocketAddress addr = mock(SocketAddress.class);
    createTransortSet(addr);

    // Invocation counters
    int transportsCreated = 0;
    int backoff1Consulted = 0;
    int backoff2Consulted = 0;
    int backoffReset = 0;

    // First attempt
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicyProvider, times(++backoffReset)).get();
    verify(mockTransportFactory, times(++transportsCreated)).newClientTransport(addr, authority);
    // Fail this one
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    // Second attempt uses the first back-off value interval.
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicy1, times(++backoff1Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    // Transport creation doesn't happen until time is due
    fakeClock.forwardMillis(9);
    verify(mockTransportFactory, times(transportsCreated)).newClientTransport(addr, authority);
    fakeClock.forwardMillis(1);
    verify(mockTransportFactory, times(++transportsCreated)).newClientTransport(addr, authority);
    // Fail this one too
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    // Third attempt uses the second back-off interval.
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicy1, times(++backoff1Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    // Transport creation doesn't happen until time is due
    fakeClock.forwardMillis(99);
    verify(mockTransportFactory, times(transportsCreated)).newClientTransport(addr, authority);
    fakeClock.forwardMillis(1);
    verify(mockTransportFactory, times(++transportsCreated)).newClientTransport(addr, authority);
    // Let this one succeed
    transports.peek().listener.transportReady();
    // And close it
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    // Back-off is reset, and the next attempt will happen immediately
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicyProvider, times(++backoffReset)).get();
    verify(mockTransportFactory, times(++transportsCreated)).newClientTransport(addr, authority);

    // Final checks for consultations on back-off policies
    verify(mockBackoffPolicy1, times(backoff1Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicy2, times(backoff2Consulted)).nextBackoffMillis();
  }

  @Test public void twoAddressesBackoff() {
    SocketAddress addr1 = mock(SocketAddress.class);
    SocketAddress addr2 = mock(SocketAddress.class);
    createTransortSet(addr1, addr2);

    // Invocation counters
    int transportsAddr1 = 0;
    int transportsAddr2 = 0;
    int backoff1Consulted = 0;
    int backoff2Consulted = 0;
    int backoff3Consulted = 0;
    int backoffReset = 0;

    // First attempt
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicyProvider, times(++backoffReset)).get();
    verify(mockTransportFactory, times(++transportsAddr1)).newClientTransport(addr1, authority);
    // Let this one through
    transports.peek().listener.transportReady();
    // Then shut it down
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);


    ////// Now start a series of failing attempts, where addr2 is the head.
    // First attempt after a connection closed. Reset back-off policy.
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicyProvider, times(++backoffReset)).get();
    verify(mockTransportFactory, times(++transportsAddr2)).newClientTransport(addr2, authority);
    // Fail this one
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    // Second attempt will start immediately. Keep back-off policy.
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    verify(mockTransportFactory, times(++transportsAddr1)).newClientTransport(addr1, authority);
    // Fail this one too
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    // Third attempt is on head, thus controlled by the first back-off interval.
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicy2, times(++backoff2Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    fakeClock.forwardMillis(9);
    verify(mockTransportFactory, times(transportsAddr2)).newClientTransport(addr2, authority);
    fakeClock.forwardMillis(1);
    verify(mockTransportFactory, times(++transportsAddr2)).newClientTransport(addr2, authority);
    // Fail this one too
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    // Forth attempt will start immediately. Keep back-off policy.
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    verify(mockTransportFactory, times(++transportsAddr1)).newClientTransport(addr1, authority);
    // Fail this one too
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    // Fifth attempt is on head, thus controlled by the second back-off interval.
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicy2, times(++backoff2Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    fakeClock.forwardMillis(99);
    verify(mockTransportFactory, times(transportsAddr2)).newClientTransport(addr2, authority);
    fakeClock.forwardMillis(1);
    verify(mockTransportFactory, times(++transportsAddr2)).newClientTransport(addr2, authority);
    // Let it through
    transports.peek().listener.transportReady();
    // Then close it.
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);


    ////// Now start a series of failing attempts, where addr1 is the head.
    // First attempt after a connection closed. Reset back-off policy.
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicyProvider, times(++backoffReset)).get();
    verify(mockTransportFactory, times(++transportsAddr1)).newClientTransport(addr1, authority);
    // Fail this one
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    // Second attempt will start immediately. Keep back-off policy.
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    verify(mockTransportFactory, times(++transportsAddr2)).newClientTransport(addr2, authority);
    // Fail this one too
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    // Third attempt is on head, thus controlled by the first back-off interval.
    transportSet.obtainActiveTransport();
    verify(mockBackoffPolicy3, times(++backoff3Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    fakeClock.forwardMillis(9);
    verify(mockTransportFactory, times(transportsAddr1)).newClientTransport(addr1, authority);
    fakeClock.forwardMillis(1);
    verify(mockTransportFactory, times(++transportsAddr1)).newClientTransport(addr1, authority);

    // Final checks on invocations on back-off policies
    verify(mockBackoffPolicy1, times(backoff1Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicy2, times(backoff2Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicy3, times(backoff3Consulted)).nextBackoffMillis();
  }

  @Test
  public void connectIsLazy() {
    SocketAddress addr = mock(SocketAddress.class);
    createTransortSet(addr);

    // Invocation counters
    int transportsCreated = 0;

    // Won't connect until requested
    verify(mockTransportFactory, times(transportsCreated)).newClientTransport(addr, authority);

    // First attempt
    assertNotNull(transportSet.obtainActiveTransport());
    verify(mockTransportFactory, times(++transportsCreated)).newClientTransport(addr, authority);

    // Fail this one
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    // Won't reconnect until requested, even if back-off time has expired
    fakeClock.forwardMillis(10);
    verify(mockTransportFactory, times(transportsCreated)).newClientTransport(addr, authority);

    // Once requested, will reconnect
    assertNotNull(transportSet.obtainActiveTransport());
    verify(mockTransportFactory, times(++transportsCreated)).newClientTransport(addr, authority);

    // Fail this one, too
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    // Request immediately, but will wait for back-off before reconnecting
    assertNotNull(transportSet.obtainActiveTransport());
    verify(mockTransportFactory, times(transportsCreated)).newClientTransport(addr, authority);
    fakeClock.forwardMillis(100);
    verify(mockTransportFactory, times(++transportsCreated)).newClientTransport(addr, authority);
  }

  @Test
  public void shutdownBeforeTransportCreatedWithPendingStream() throws Exception {
    SocketAddress addr = mock(SocketAddress.class);
    createTransortSet(addr);

    // First transport is created immediately
    ListenableFuture<ClientTransport> pick = transportSet.obtainActiveTransport();
    verify(mockTransportFactory).newClientTransport(addr, authority);
    assertTrue(pick.isDone());
    assertNotNull(pick.get());
    // Fail this one
    MockClientTransportInfo transportInfo = transports.poll();
    transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    transportInfo.listener.transportTerminated();

    // Second transport will wait for back-off
    pick = transportSet.obtainActiveTransport();
    assertTrue(pick.isDone());
    assertTrue(pick.get() instanceof DelayedClientTransport);
    // Start a stream, which will be pending in the delayed transport
    ClientStream pendingStream = pick.get().newStream(method, headers);
    pendingStream.start(mockStreamListener);

    // Shut down TransportSet before the transport is created. Further call to
    // obtainActiveTransport() gets null results.
    transportSet.shutdown();
    pick = transportSet.obtainActiveTransport();
    assertTrue(pick.isDone());
    assertNull(pick.get());
    verify(mockTransportFactory).newClientTransport(addr, authority);

    // Reconnect will eventually happen, even though TransportSet has been shut down
    fakeClock.forwardMillis(10);
    verify(mockTransportFactory, times(2)).newClientTransport(addr, authority);
    // The pending stream will be started on this newly started transport, which is promptly shut
    // down by TransportSet right after the stream is created.
    transportInfo = transports.poll();
    verify(transportInfo.transport).newStream(same(method), same(headers));
    verify(transportInfo.transport).shutdown();
    verify(mockTransportSetCallback, never()).onTerminated();
    // Terminating the transport will let TransportSet to be terminated.
    transportInfo.listener.transportTerminated();
    verify(mockTransportSetCallback).onTerminated();

    // No more transports will be created.
    fakeClock.forwardMillis(10000);
    verifyNoMoreInteractions(mockTransportFactory);
    assertEquals(0, transports.size());
  }

  @Test
  public void shutdownBeforeTransportCreatedWithoutPendingStream() throws Exception {
    SocketAddress addr = mock(SocketAddress.class);
    createTransortSet(addr);

    // First transport is created immediately
    ListenableFuture<ClientTransport> pick = transportSet.obtainActiveTransport();
    verify(mockTransportFactory).newClientTransport(addr, authority);
    assertTrue(pick.isDone());
    assertNotNull(pick.get());
    // Fail this one
    MockClientTransportInfo transportInfo = transports.poll();
    transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    transportInfo.listener.transportTerminated();

    // Second transport will wait for back-off
    pick = transportSet.obtainActiveTransport();
    assertTrue(pick.isDone());
    assertTrue(pick.get() instanceof DelayedClientTransport);

    // Shut down TransportSet before the transport is created. Futher call to
    // obtainActiveTransport() gets null results.
    transportSet.shutdown();
    pick = transportSet.obtainActiveTransport();
    assertTrue(pick.isDone());
    assertNull(pick.get());

    // TransportSet terminated promptly.
    verify(mockTransportSetCallback).onTerminated();

    // No more transports will be created.
    fakeClock.forwardMillis(10000);
    verifyNoMoreInteractions(mockTransportFactory);
    assertEquals(0, transports.size());
  }

  @Test
  public void obtainTransportAfterShutdown() throws Exception {
    SocketAddress addr = mock(SocketAddress.class);
    createTransortSet(addr);

    transportSet.shutdown();
    ListenableFuture<ClientTransport> pick = transportSet.obtainActiveTransport();
    assertNotNull(pick);
    assertTrue(pick.isDone());
    assertNull(pick.get());
    verify(mockTransportFactory, times(0)).newClientTransport(addr, authority);
  }

  @Test
  public void futuresAreAlwaysComplete() throws Exception {
    SocketAddress addr = mock(SocketAddress.class);
    createTransortSet(addr);

    // Fail the first pick so that the next pick will be pending on back-off
    ListenableFuture<ClientTransport> future0 = transportSet.obtainActiveTransport();
    assertTrue(future0.isDone());
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);

    ListenableFuture<ClientTransport> future1 = transportSet.obtainActiveTransport();
    ListenableFuture<ClientTransport> future2 = transportSet.obtainActiveTransport();

    // These futures are pending on back-off
    assertTrue(future1.isDone());
    assertTrue(future2.isDone());

    assertTrue(future1.get() instanceof DelayedClientTransport);
    assertTrue(future2.get() instanceof DelayedClientTransport);
  }

  private void createTransortSet(SocketAddress ... addrs) {
    addressGroup = new EquivalentAddressGroup(Arrays.asList(addrs));
    transportSet = new TransportSet(addressGroup, authority, mockLoadBalancer,
        mockBackoffPolicyProvider, mockTransportFactory, fakeClock.scheduledExecutorService,
        mockTransportSetCallback, Stopwatch.createUnstarted(fakeClock.ticker));
  }
}
