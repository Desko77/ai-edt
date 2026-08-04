/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads a JUnit XML report into {@link JUnitRunOutcome}.
 * <p>
 * Both 1C test runners this plugin drives - YAXUnit and Vanessa Automation - report in the JUnit
 * dialect, and neither is entirely faithful to it: one may state its totals in the {@code testsuite}
 * attributes and leave the detail nodes out, another may do the reverse. So each counter is taken as
 * the larger of what the suite claims and what its test cases actually show. Whichever half of the
 * report is missing, the numbers still come out right.
 * </p>
 * <p>
 * The parser is locked down against XML external entities (see {@link #newSecureBuilder()}). A report
 * is a file this plugin wrote itself, but it is still XML from a third-party process, and a document
 * that can name {@code file:///} is a document that can read the disk.
 * </p>
 */
public final class JUnitXmlReader
{
    private static final String TAG_TESTSUITE = "testsuite"; //$NON-NLS-1$
    private static final String TAG_TESTCASE = "testcase"; //$NON-NLS-1$
    private static final String TAG_FAILURE = "failure"; //$NON-NLS-1$
    private static final String TAG_ERROR = "error"; //$NON-NLS-1$
    private static final String TAG_SKIPPED = "skipped"; //$NON-NLS-1$

    private static final String ATTR_TESTS = "tests"; //$NON-NLS-1$
    private static final String ATTR_FAILURES = "failures"; //$NON-NLS-1$
    private static final String ATTR_ERRORS = "errors"; //$NON-NLS-1$
    private static final String ATTR_SKIPPED = "skipped"; //$NON-NLS-1$
    private static final String ATTR_NAME = "name"; //$NON-NLS-1$
    private static final String ATTR_CLASSNAME = "classname"; //$NON-NLS-1$
    private static final String ATTR_MESSAGE = "message"; //$NON-NLS-1$

    private JUnitXmlReader()
    {
        // utility
    }

    /**
     * Reads a report file.
     *
     * @param junitXml the report; must exist and be readable
     * @return what the report says
     * @throws Exception if the file cannot be read, is not well-formed XML, or declares a DOCTYPE
     */
    public static JUnitRunOutcome parse(File junitXml) throws Exception
    {
        return collect(newSecureBuilder().parse(junitXml));
    }

    /**
     * Reads a report from a stream. The stream is left open; the caller owns it.
     *
     * @param in the report bytes
     * @return what the report says
     * @throws Exception if the stream cannot be read, is not well-formed XML, or declares a DOCTYPE
     */
    public static JUnitRunOutcome parse(InputStream in) throws Exception
    {
        return collect(newSecureBuilder().parse(in));
    }

    /**
     * Builds a document parser that will not reach outside the document it is given.
     * <p>
     * The decisive one is {@code disallow-doctype-decl}: with no DOCTYPE there are no entity
     * declarations, so external entities, entity expansion bombs and the {@code file:///} read that
     * XXE is named for all become parse errors rather than behaviour. The rest - no external general
     * or parameter entities, no external DTD, no XInclude, no entity expansion, and an empty allow-list
     * for external DTD and schema access - close the same door from the other side, so that a JDK whose
     * parser honours one setting and not another still ends up shut.
     * </p>
     *
     * @return a fresh builder, safe to use once
     * @throws ParserConfigurationException if the JDK parser rejects one of the settings, in which
     *         case the safe move is to fail rather than parse unprotected
     */
    private static DocumentBuilder newSecureBuilder() throws ParserConfigurationException
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); //$NON-NLS-1$
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); //$NON-NLS-1$
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); //$NON-NLS-1$
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); //$NON-NLS-1$
        return factory.newDocumentBuilder();
    }

    /**
     * Walks the parsed document and adds up what it finds.
     * <p>
     * Every {@code testsuite} in the document is visited once, at whatever depth it sits, so a
     * {@code testsuites} wrapper needs no special handling. Each test case belongs to the nearest suite
     * above it and is counted there and nowhere else - which is what keeps a report whose suites nest
     * from counting its inner tests twice, once for the inner suite and again for the outer.
     * </p>
     * <p>
     * A suite that contains other suites is read as a container: its own attributes summarise its
     * children, so trusting them as well as the children's would inflate the same numbers again. Only
     * the test cases it holds directly count for it. A suite with no nested suites - which is every
     * suite YAXUnit and Vanessa emit - is read as before: for each counter, the larger of the attribute
     * and the nodes.
     * </p>
     *
     * @param doc the parsed report
     * @return the totals and the details
     */
    private static JUnitRunOutcome collect(Document doc)
    {
        doc.getDocumentElement().normalize();

        JUnitRunOutcome results = new JUnitRunOutcome();

        NodeList suites = doc.getElementsByTagName(TAG_TESTSUITE);
        if (suites.getLength() == 0)
        {
            // No suite to read counters from - the test cases are the whole story.
            results.setTotal(doc.getElementsByTagName(TAG_TESTCASE).getLength());
            return results;
        }

        for (int i = 0; i < suites.getLength(); i++)
        {
            collectSuite((Element)suites.item(i), results);
        }
        return results;
    }

    /**
     * Adds one suite's tests to the results.
     *
     * @param suite the suite element
     * @param results the results being built
     */
    private static void collectSuite(Element suite, JUnitRunOutcome results)
    {
        List<Element> testCases = new ArrayList<>();
        collectOwnTestCases(suite, testCases);

        int countedFailures = 0;
        int countedErrors = 0;
        int countedSkipped = 0;

        for (Element testCase : testCases)
        {
            String name = fullName(testCase);

            Element failure = firstDescendant(testCase, TAG_FAILURE);
            if (failure != null)
            {
                countedFailures++;
                results.addFailure(new JUnitRunOutcome.TestCase(name, failure.getAttribute(ATTR_MESSAGE),
                    failure.getTextContent()));
            }

            Element error = firstDescendant(testCase, TAG_ERROR);
            if (error != null)
            {
                countedErrors++;
                results.addError(
                    new JUnitRunOutcome.TestCase(name, error.getAttribute(ATTR_MESSAGE), error.getTextContent()));
            }

            Element skipped = firstDescendant(testCase, TAG_SKIPPED);
            if (skipped != null)
            {
                countedSkipped++;
                results.addSkipped(new JUnitRunOutcome.TestCase(name, skipped.getAttribute(ATTR_MESSAGE), null));
            }
        }

        boolean container = suite.getElementsByTagName(TAG_TESTSUITE).getLength() > 0;
        if (container)
        {
            results.addToTotals(testCases.size(), countedFailures, countedErrors, countedSkipped);
            return;
        }

        results.addToTotals(Math.max(intAttribute(suite, ATTR_TESTS), testCases.size()),
            Math.max(intAttribute(suite, ATTR_FAILURES), countedFailures),
            Math.max(intAttribute(suite, ATTR_ERRORS), countedErrors),
            Math.max(intAttribute(suite, ATTR_SKIPPED), countedSkipped));
    }

    /**
     * Gathers the test cases that belong to this suite and not to a suite nested inside it.
     * <p>
     * The walk goes down through anything that is not a suite, so a producer that wraps its cases in
     * some grouping element of its own is still read, and stops at a nested suite, which will be
     * visited in its own right.
     * </p>
     *
     * @param parent the node to descend from
     * @param out collects the test case elements, in document order
     */
    private static void collectOwnTestCases(Node parent, List<Element> out)
    {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE)
            {
                continue;
            }

            Element element = (Element)child;
            String tag = element.getTagName();
            if (TAG_TESTSUITE.equals(tag))
            {
                continue;
            }
            if (TAG_TESTCASE.equals(tag))
            {
                out.add(element);
                continue;
            }
            collectOwnTestCases(element, out);
        }
    }

    /**
     * @param testCase the test case element
     * @return the module-qualified test name, or the bare name when the report gave no module
     */
    private static String fullName(Element testCase)
    {
        String name = testCase.getAttribute(ATTR_NAME);
        String className = testCase.getAttribute(ATTR_CLASSNAME);
        return className.isEmpty() ? name : className + "." + name; //$NON-NLS-1$
    }

    /**
     * @param element the element to search below
     * @param tag the tag to look for
     * @return the first such descendant, or <code>null</code> when there is none
     */
    private static Element firstDescendant(Element element, String tag)
    {
        NodeList found = element.getElementsByTagName(tag);
        return found.getLength() == 0 ? null : (Element)found.item(0);
    }

    /**
     * Reads a counter attribute.
     *
     * @param element the element carrying it
     * @param name the attribute name
     * @return its value, or 0 when it is absent, empty or not a number
     */
    private static int intAttribute(Element element, String name)
    {
        String raw = element.getAttribute(name).trim();
        if (raw.isEmpty())
        {
            return 0;
        }
        try
        {
            return Integer.parseInt(raw);
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }
}
