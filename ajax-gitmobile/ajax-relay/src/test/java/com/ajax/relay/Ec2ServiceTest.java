package com.ajax.relay;

import com.ajax.relay.model.InstanceStatus;
import com.ajax.relay.service.Ec2Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.StartInstancesRequest;
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Ec2ServiceTest {

    private Ec2Client ec2;
    private Ec2Service service;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Client.class);
        service = new Ec2Service(ec2);
        ReflectionTestUtils.setField(service, "instanceId", "i-test");
    }

    @Test
    void describeMapsRunning() {
        when(ec2.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(response(InstanceStateName.RUNNING));
        assertThat(service.describe()).isEqualTo(InstanceStatus.RUNNING);
    }

    @Test
    void describeMapsStopped() {
        when(ec2.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(response(InstanceStateName.STOPPED));
        assertThat(service.describe()).isEqualTo(InstanceStatus.STOPPED);
    }

    @Test
    void startCallsAws() {
        service.start();
        verify(ec2).startInstances(any(StartInstancesRequest.class));
    }

    @Test
    void stopCallsAws() {
        service.stop();
        verify(ec2).stopInstances(any(StopInstancesRequest.class));
    }

    private DescribeInstancesResponse response(InstanceStateName name) {
        return DescribeInstancesResponse.builder()
                .reservations(Reservation.builder()
                        .instances(Instance.builder()
                                .instanceId("i-test")
                                .state(InstanceState.builder().name(name).build())
                                .build())
                        .build())
                .build();
    }
}
