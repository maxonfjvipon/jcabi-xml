/*
 * Copyright (c) 2012-2021, jcabi.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 1) Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer. 2) Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution. 3) Neither the name of the jcabi.com nor
 * the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jcabi.xml;

import com.google.common.collect.Iterables;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.transform.Source;
import javax.xml.validation.Validator;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

/**
 * Test case for {@link StrictXML}.
 * @since 0.1
 * @checkstyle MultipleStringLiteralsCheck (500 lines)
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 * @checkstyle AbbreviationAsWordInNameCheck (5 lines)
 */
@SuppressWarnings({ "PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
public final class StrictXMLTest {

    @BeforeEach
    public void weAreOnline() throws IOException {
        Assumptions.assumeTrue(
            InetAddress.getByName("w3.org").isReachable(1000)
        );
    }

    @Test
    public void passesValidXmlThrough() {
        new StrictXML(
            new XMLDocument("<root>passesValidXmlThrough</root>"),
            new XSDDocument(
                StringUtils.join(
                    "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'>",
                    "<xs:element name='root' type='xs:string'/>",
                    "</xs:schema>"
                )
            )
        );
    }

    @Test
    public void rejectsInvalidXmlThrough() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new StrictXML(
                new XMLDocument("<root>not an integer</root>"),
                new XSDDocument(
                    StringUtils.join(
                        "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema' >",
                        "<xs:element name='root' type='xs:integer'/></xs:schema>"
                    )
                )
            )
        );
    }

    @Test
    public void passesValidXmlUsingXsiSchemaLocation() throws Exception {
        new StrictXML(
            new XMLDocument(
                this.getClass().getResource("xsi-schemalocation-valid.xml")
            )
        );
    }

    @Test
    public void rejectsInvalidXmlUsingXsiSchemaLocation() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new StrictXML(
                new XMLDocument(
                    this.getClass().getResource("xsi-schemalocation-invalid.xml")
                )
            )
        );
    }

    @Test
    public void validatesMultipleXmlsInThreads() throws Exception {
        final int timeout = 10;
        final int numrun = 100;
        final int loop = 50;
        final XSD xsd = new XSDDocument(
            StringUtils.join(
                "<xs:schema xmlns:xs ='http://www.w3.org/2001/XMLSchema' >",
                "<xs:element name='r'><xs:complexType><xs:sequence>",
                "<xs:element name='x' maxOccurs='unbounded'><xs:simpleType>",
                "<xs:restriction base='xs:integer'>",
                "<xs:maxInclusive value='100'/></xs:restriction>",
                "</xs:simpleType></xs:element>",
                "</xs:sequence></xs:complexType></xs:element></xs:schema>"
            )
        );
        final Random rnd = new SecureRandom();
        final XML xml = new XMLDocument(
            StringUtils.join(
                Iterables.concat(
                    Collections.singleton("<r>"),
                    Iterables.transform(
                        Collections.nCopies(timeout, 0),
                        pos -> String.format(
                            "<x>%d</x>", rnd.nextInt(numrun)
                        )
                    ),
                    Collections.singleton("<x>101</x></r>")
                ),
                " "
            )
        );
        final AtomicInteger done = new AtomicInteger();
        final Callable<Void> callable = () -> {
            try {
                new StrictXML(xml, xsd);
            } catch (final IllegalArgumentException ex) {
                done.incrementAndGet();
            }
            return null;
        };
        final ExecutorService service = Executors.newFixedThreadPool(5);
        for (int count = 0; count < loop; count += 1) {
            service.submit(callable);
        }
        service.shutdown();
        MatcherAssert.assertThat(
            service.awaitTermination(timeout, TimeUnit.SECONDS),
            Matchers.is(true)
        );
        service.shutdownNow();
        MatcherAssert.assertThat(done.get(), Matchers.equalTo(loop));
    }

    @Test
    public void passesValidXmlWithNetworkProblems() throws Exception {
        final Validator validator = Mockito.mock(Validator.class);
        final AtomicInteger counter = new AtomicInteger(0);
        // @checkstyle IllegalThrowsCheck (5 lines)
        Mockito.doAnswer(
            (Answer<Void>) invocation -> {
                final int attempt = counter.incrementAndGet();
                if (attempt == 1 || attempt == 2) {
                    throw new SocketException(
                        String.format("Attempt #%s failed", attempt)
                    );
                }
                return null;
            }
        ).when(validator).validate(Mockito.any(Source.class));
        new StrictXML(
            new XMLDocument(
                "<root>passesValidXmlWithNetworkProblems</root>"
            ),
            validator
        );
    }

    @Test
    public void lookupXsdsFromClasspath() {
        new StrictXML(
            new XMLDocument(
                StringUtils.join(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                    "<payment xmlns=\"http://jcabi.com/schema/xml\" ",
                    "xmlns:xsi=\"",
                    "http://www.w3.org/2001/XMLSchema-instance",
                    "\" ",
                    "xsi:schemaLocation=\"",
                    "http://jcabi.com/schema/xml ",
                    "com/jcabi/xml/sample-namespaces.xsd",
                    "\">",
                    "<id>333</id>",
                    "<date>1-Jan-2013</date>",
                    "<debit>test-1</debit>",
                    "<credit>test-2</credit>",
                    "</payment>"
                )
            )
        );
    }

    @Test
    public void rejectXmlWhenXsdIsNotAvailableOnClasspath() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new StrictXML(
                new XMLDocument(
                    StringUtils.join(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                        "<payment xmlns=\"http://jcabi.com/schema/xml\" ",
                        "xmlns:xsi=\"",
                        "http://www.w3.org/2001/XMLSchema-instance",
                        "\" ",
                        "xsi:schemaLocation=\"",
                        "http://jcabi.com/schema/xml ",
                        "sample-non-existing.xsd",
                        "\">",
                        "<id>333</id>",
                        "<date>1-Jan-2013</date>",
                        "<debit>test-1</debit>",
                        "<credit>test-2</credit>",
                        "</payment>"
                    )
                )
            )
        );
    }

    @Test
    public void handlesXmlWithoutSchemaLocation() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new StrictXML(
                new XMLDocument("<a></a>")
            )
        );
    }
}
