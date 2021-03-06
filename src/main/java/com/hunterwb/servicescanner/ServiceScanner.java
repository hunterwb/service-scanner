package com.hunterwb.servicescanner;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Processes all types for {@link ServiceLoader} providers and generates their configuration files.
 * <p>
 * Processor Options:<ul>
 *     <li>services - comma delimited list of the fully qualified binary names of the services to look for</li>
 * </ul>
 */
public final class ServiceScanner extends UniversalProcessor {

    private final Map<String, Set<String>> serviceProviders = new TreeMap<String, Set<String>>();

    @Override
    public Set<String> getSupportedOptions() {
        return Collections.singleton("services");
    }

    @Override
    void init() {
        String services = option("services");
        if (services == null || services.isEmpty()) {
            log(Diagnostic.Kind.WARNING, "No services added. Add services by passing their fully qualified binary names to javac in the following format:");
            log(Diagnostic.Kind.WARNING, "-Aservices=com.example.Service1,com.example.Service2");
            return;
        }
        for (String service : services.split(",")) {
            serviceProviders.put(service, new TreeSet<String>());
        }
    }

    @Override
    void process(RoundEnvironment roundEnv) {
        if (serviceProviders.isEmpty()) return;
        for (Element e : roundEnv.getRootElements()) {
            // skip packages / modules
            if (isType(e)) {
                TypeElement t = (TypeElement) e;
                // assert t.getNestingKind() == NestingKind.TOP_LEVEL;
                process(t);
            }
        }
    }

    private void process(TypeElement t) {
        if (isServiceProviderCandidate(t)) {
            for (Map.Entry<String, Set<String>> entry : serviceProviders.entrySet()) {
                String service = entry.getKey();
                Set<String> providers = entry.getValue();

                if (isSubType(t.asType(), service)) {
                    providers.add(elements().getBinaryName(t).toString());
                }
            }
        }
        for (Element enclosed : t.getEnclosedElements()) {
            if (isType(enclosed)) {
                TypeElement enclosedType = (TypeElement) enclosed;
                // assert enclosedType.getNestingKind() == NestingKind.MEMBER;
                process(enclosedType);
            }
        }
    }

    private boolean isServiceProviderCandidate(TypeElement e) {
        return e.getKind() == ElementKind.CLASS &&
                e.getModifiers().contains(Modifier.PUBLIC) &&
                !e.getModifiers().contains(Modifier.ABSTRACT) &&
                (e.getNestingKind() == NestingKind.TOP_LEVEL || e.getModifiers().contains(Modifier.STATIC)) &&
                hasDefaultConstructor(e);
    }

    private static boolean isType(Element e) {
        ElementKind k = e.getKind();
        return k.isClass() || k.isInterface();
    }

    private boolean isSubType(TypeMirror sub, String parentName) {
        if (elements().getBinaryName((TypeElement) types().asElement(sub)).contentEquals(parentName)) return true;
        for (TypeMirror ds : types().directSupertypes(sub)) {
            if (isSubType(ds, parentName)) return true;
        }
        return false;
    }

    private boolean hasDefaultConstructor(TypeElement t) {
        for (Element enclosed : t.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement constructor = (ExecutableElement) enclosed;
            if (!constructor.getModifiers().contains(Modifier.PUBLIC)) continue;
            if (!constructor.getParameters().isEmpty()) continue;
            return true;
        }
        return false;
    }

    @Override
    void end() {
        Charset utf8 = Charset.forName("UTF-8");
        for (Map.Entry<String, Set<String>> entry : serviceProviders.entrySet()) {
            String service = entry.getKey();
            Set<String> providers = entry.getValue();
            log(Diagnostic.Kind.NOTE, "Found providers " + providers + " for service " + service);
            String serviceFileName = "META-INF/services/" + service;

            if (fileExists(serviceFileName)) {
                log(Diagnostic.Kind.WARNING, "Overwriting file " + serviceFileName);
            }

            try {
                OutputStream out = openFileOutput(serviceFileName);
                try {
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, utf8));
                    for (String provider : providers) {
                        writer.write(provider);
                        writer.newLine();
                    }
                    writer.flush();
                } finally {
                    out.close();
                }
            } catch (IOException e) {
                log(Diagnostic.Kind.ERROR, e.toString());
                return;
            }
        }
    }
}
