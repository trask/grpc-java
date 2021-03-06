// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: load_balancer.proto

package io.grpc.grpclb;

public interface LoadBalanceResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:loadbalancer_gslb.client.grpc.LoadBalanceResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>optional .loadbalancer_gslb.client.grpc.InitialLoadBalanceResponse initial_response = 1;</code>
   *
   * <pre>
   * This message should be sent on the first response to the client.
   * </pre>
   */
  io.grpc.grpclb.InitialLoadBalanceResponse getInitialResponse();
  /**
   * <code>optional .loadbalancer_gslb.client.grpc.InitialLoadBalanceResponse initial_response = 1;</code>
   *
   * <pre>
   * This message should be sent on the first response to the client.
   * </pre>
   */
  io.grpc.grpclb.InitialLoadBalanceResponseOrBuilder getInitialResponseOrBuilder();

  /**
   * <code>optional .loadbalancer_gslb.client.grpc.ServerList server_list = 2;</code>
   *
   * <pre>
   * Contains the list of servers selected by the load balancer. The client
   * should send requests to these servers in the specified order.
   * </pre>
   */
  io.grpc.grpclb.ServerList getServerList();
  /**
   * <code>optional .loadbalancer_gslb.client.grpc.ServerList server_list = 2;</code>
   *
   * <pre>
   * Contains the list of servers selected by the load balancer. The client
   * should send requests to these servers in the specified order.
   * </pre>
   */
  io.grpc.grpclb.ServerListOrBuilder getServerListOrBuilder();

  public io.grpc.grpclb.LoadBalanceResponse.LoadBalanceResponseTypeCase getLoadBalanceResponseTypeCase();
}
