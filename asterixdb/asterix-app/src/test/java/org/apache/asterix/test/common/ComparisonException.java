/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.test.common;

public class ComparisonException extends Exception {

    enum Type {
        DIFFERENT_RESULT,
        MALFORMED_RESULT,
        NO_RESULT
    }

    private static final long serialVersionUID = 1L;

    private final Type exceptionType;

    private ComparisonException(String message, Type type) {
        super(message);
        exceptionType = type;
    }

    private ComparisonException(String message, Throwable cause, Type type) {
        super(message, cause);
        exceptionType = type;
    }

    public static ComparisonException noResult(String message) {
        return new ComparisonException(message, Type.NO_RESULT);
    }

    public static ComparisonException differentResult(String message) {
        return new ComparisonException(message, Type.DIFFERENT_RESULT);
    }

    public static ComparisonException malformedResult(String message, Throwable cause) {
        return new ComparisonException(message, cause, Type.MALFORMED_RESULT);
    }

    public Type getExceptionType() {
        return exceptionType;
    }
}
