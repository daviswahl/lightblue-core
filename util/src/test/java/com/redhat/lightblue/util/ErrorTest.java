/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.redhat.lightblue.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author nmalik
 */
public class ErrorTest {
    @Before
    public void setup() {
        Error.reset();
    }

    /**
     * Test of push method, of class Error.
     */
    @Test
    public void testPush() {
        String contexts[] = new String[]{"1", "2"};
        String errorCode = "code";

        StringBuilder buff = new StringBuilder();

        for (String context : contexts) {
            if (buff.length() > 0) {
                buff.append(Error.DELIMITER);
            }
            Error.push(context);
            buff.append(context);
        }

        Error e = Error.get(errorCode);

        Assert.assertEquals(buff.toString(), e.getContext());
        Assert.assertEquals(errorCode, e.getErrorCode());
    }

    /**
     * Test of pop method, of class Error.
     */
    @Test
    public void testPop() {
        String contexts[] = new String[]{"1", "2"};
        String errorCode = "code";

        for (String context : contexts) {
            Error.push(context);
        }

        Error.pop();

        // testPush takes care of verifying context on pushes.. pop it and test only the first context exists still
        Error e = Error.get(errorCode);
        Assert.assertEquals(contexts[0], e.getContext());
        Assert.assertEquals(errorCode, e.getErrorCode());
    }

    /**
     * Test of get method, of class Error.
     */
    @Test
    public void testGet_3args() {
        String ctx = "context";
        String errorCode = "code";
        String msg = "message";
        Error result = Error.get(ctx, errorCode, msg);

        Assert.assertEquals(ctx, result.getContext());
        Assert.assertEquals(errorCode, result.getErrorCode());
        Assert.assertEquals(msg, result.getMsg());
    }

    /**
     * Test of get method, of class Error.
     */
    @Test
    public void testGet_2args() {
        String errorCode = "code";
        String msg = "message";
        Error result = Error.get(errorCode, msg);

        Assert.assertEquals("", result.getContext());
        Assert.assertEquals(errorCode, result.getErrorCode());
        Assert.assertEquals(msg, result.getMsg());
    }

    /**
     * Test of get method, of class Error.
     */
    @Test
    public void testGet_1arg() {
        String errorCode = "code";
        Error result = Error.get(errorCode);

        Assert.assertEquals("", result.getContext());
        Assert.assertEquals(errorCode, result.getErrorCode());
        Assert.assertNull(result.getMsg());
    }

    /**
     * Test of reset method, of class Error.
     */
    @Test
    public void testReset() {
        String context = "test";
        Error.push(context);

        Error before = Error.get("code");

        Assert.assertEquals(context, before.getContext());

        Error.reset();

        Error after = Error.get("code");

        Assert.assertEquals("", after.getContext());
    }

    /**
     * Test of pushContext method, of class Error.
     */
    @Test
    public void testPushContext() {
        List<String> contexts = new ArrayList<>();
        contexts.add("1");
        contexts.add("2");

        for (String context : contexts) {
            Error.push(context);
        }

        Error result = Error.get("code");

        String pushContext = "3";
        contexts.add(pushContext);
        result.pushContext(pushContext);

        StringBuilder buff = new StringBuilder();

        for (String context : contexts) {
            if (buff.length() > 0) {
                buff.append(Error.DELIMITER);
            }
            buff.append(context);
        }

        Assert.assertEquals(buff.toString(), result.getContext());
    }

    /**
     * Test of popContext method, of class Error.
     */
    @Test
    public void testPopContext() {
        List<String> contexts = new ArrayList<>();
        contexts.add("1");
        contexts.add("2");

        for (String context : contexts) {
            Error.push(context);
        }

        Error result = Error.get("code");

        String pushContext = "3";
        result.pushContext(pushContext);
        result.popContext();

        StringBuilder buff = new StringBuilder();

        for (String context : contexts) {
            if (buff.length() > 0) {
                buff.append(Error.DELIMITER);
            }
            buff.append(context);
        }

        Assert.assertEquals(buff.toString(), result.getContext());
    }
}