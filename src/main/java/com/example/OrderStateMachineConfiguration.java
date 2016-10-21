package com.example;

import java.util.EnumSet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.guard.Guard;
import org.springframework.statemachine.listener.StateMachineListener;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;

import com.example.OrderStateMachineConfiguration.OrderEvent;
import com.example.OrderStateMachineConfiguration.OrderState;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
@EnableStateMachineFactory
class OrderStateMachineConfiguration extends EnumStateMachineConfigurerAdapter<OrderState, OrderEvent> {

    /*
     * pre-payment flow
     * 
     *   (0)
     *    |            (1)                              (2)[if paid]
     *  Open -> (ReceivePayment) -> ReadyForDelivery -> (Deliver) ->  Completed
     *    ^                          |   |  [if paid]                    |
     *    |                          |   '--(3)---> (Refund) <---(4)-----'
     *    |                         (8)                 |
     *    |                          | (Cancel)         v
     *    |                   (5/6)  '---------->  Canceled <-
     *    '-----------------(Reopen/Cancel)------------'
     */

    /*
     * post-payment flow
     * 
     *   (0)
     *    |
     *    |          (7)             (11) (ReceivePayment)   (9)[if !paid]                         (10)
     *  Open -> (UnlockDelivery)  --->  ReadyForDelivery  ->  (Deliver) ->  AwaitingPayment -> (ReceivePayment) -> Completed
     *    ^                             | |  [if paid]                                                                |
     *    |                             | '--(3)---> (Refund) <----------------(4)------------------------------------'
     *    |                            (8)               |
     *    |                             | (Cancel)       v
     *    |                   (5/6)     '--------->  Canceled <- (12) (ReceivePayment)
     *    '-----------------(Reopen/Cancel)--------------'
     *    
     *    (11) and (12) are internal transitions that do not change state, but only update the payment flag.
     */

    public enum OrderState {
        Open,
        ReadyForDelivery,
        AwaitingPayment,
        Completed,
        Canceled
    }

    public static enum OrderEvent {
        UnlockDelivery,
        ReceivePayment,
        Refund,
        Deliver,
        Reopen,
        Cancel
    }

    @Override
    public void configure(StateMachineConfigurationConfigurer<OrderState, OrderEvent> config) throws Exception {
        config
        .withConfiguration()
        .listener(loggingListener());
    }

    @Override
    public void configure(StateMachineStateConfigurer<OrderState, OrderEvent> states)
            throws Exception {
        states
            .withStates()
                .initial(OrderState.Open, context -> setUnpaid(context.getExtendedState()))
                .states(EnumSet.allOf(OrderState.class));
    }

    public StateMachineListener<OrderState, OrderEvent> loggingListener() {
        return new StateMachineListenerAdapter<OrderState, OrderEvent>() {
            @Override
            public void stateChanged(State<OrderState, OrderEvent> from, State<OrderState, OrderEvent> to) {
                log.info("State changed to {}", to.getId());
            }
            @Override
            public void eventNotAccepted(Message<OrderEvent> event) {
                log.error("Event not accepted: {}", event.getPayload());
            }
        };
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderState, OrderEvent> transitions)
            throws Exception {
        transitions
            .withExternal()
            // (1)
                .source(OrderState.Open)
                .target(OrderState.ReadyForDelivery)
                .event(OrderEvent.ReceivePayment)
                .action(receivePayment())
            .and()
            // (2)
            .withExternal()
                .source(OrderState.ReadyForDelivery)
                .target(OrderState.Completed)
                .guard(isPaid())
                .event(OrderEvent.Deliver)
            .and()
            // (3)
            .withExternal()
                .source(OrderState.ReadyForDelivery)
                .target(OrderState.Canceled)
                .guard(isPaid())
                .event(OrderEvent.Refund)
                .action(refundPayment())
            .and()
            // (4)
            .withExternal()
                .source(OrderState.Completed)
                .target(OrderState.Canceled)
                .event(OrderEvent.Refund)
                .action(refundPayment())
            .and()
            // (5)
            .withExternal()
                .source(OrderState.Canceled)
                .target(OrderState.Open)
                .event(OrderEvent.Reopen)
            .and()
            // (6)
            .withExternal()
                .source(OrderState.Open)
                .target(OrderState.Canceled)
                .event(OrderEvent.Cancel)
            .and()
            // (7)
            .withExternal()
                .source(OrderState.Open)
                .target(OrderState.ReadyForDelivery)
                .event(OrderEvent.UnlockDelivery)
            .and()
            // (8)
            .withExternal()
                .source(OrderState.ReadyForDelivery)
                .target(OrderState.Canceled)
                .event(OrderEvent.Cancel)
            .and()
            // (9)
            .withExternal()
                .source(OrderState.ReadyForDelivery)
                .target(OrderState.AwaitingPayment)
                .guard(not(isPaid()))
                .event(OrderEvent.Deliver)
            .and()
            // (10)
            .withExternal()
                .source(OrderState.AwaitingPayment)
                .target(OrderState.Completed)
                .event(OrderEvent.ReceivePayment)
                .action(receivePayment())
            .and()
            // (11)
            .withInternal()
                .source(OrderState.ReadyForDelivery)
                .event(OrderEvent.ReceivePayment)
                .action(receivePayment())
            .and()
            // (12)
            .withInternal()
                .source(OrderState.Canceled)
                .event(OrderEvent.ReceivePayment)
                .action(receivePayment())
            .and()
            ;
    }

    @Bean
    public Action<OrderState, OrderEvent> receivePayment() {
        return context -> setPaid(context.getExtendedState());
    }

    @Bean
    public Action<OrderState, OrderEvent> refundPayment() {
        return context -> setUnpaid(context.getExtendedState());
    }

    private Guard<OrderState, OrderEvent> isPaid() {
        return context -> 
            (boolean) context.getExtendedState().get("paid", Boolean.class);
    }

    private Guard<OrderState, OrderEvent> not(Guard<OrderState, OrderEvent> guard) {
        return context -> !guard.evaluate(context);
    }

    void setUnpaid(ExtendedState extendedState) {
        log.info("Unsetting paid");
        extendedState.getVariables().put("paid", Boolean.FALSE);
    }

    void setPaid(ExtendedState extendedState) {
        log.info("Setting paid");
        extendedState.getVariables().put("paid", Boolean.TRUE);
    }

}