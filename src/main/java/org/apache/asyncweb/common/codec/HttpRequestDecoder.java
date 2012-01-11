/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.asyncweb.common.codec;

import java.util.List;

import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.statemachine.DecodingState;
import org.apache.mina.filter.codec.statemachine.DecodingStateProtocolDecoder;

/**
 * An HttpRequest decoder.
 * 
 * @author The Apache MINA Project (dev@mina.apache.org)
 */
public class HttpRequestDecoder extends DecodingStateProtocolDecoder {
    public HttpRequestDecoder() {
        super(new HttpRequestDecodingStateMachine() {
            @Override
            protected DecodingState finishDecode(List<Object> childProducts, ProtocolDecoderOutput out)
                    throws Exception {
                for (Object m : childProducts) {
                    out.write(m);
                }

                return null;
            }
        });
    }
}
