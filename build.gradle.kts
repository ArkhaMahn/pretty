import org.zaproxy.gradle.addon.AddOnStatus

plugins {
    java
    id("org.zaproxy.add-on") version "0.13.1"
}

group = "org.zaproxy.zap.extension"
version = "1.0.0"
description = "Pretty view for the HTTP Request and HTTP Response panels: auto-detects the payload format (JSON, HTML, XML, CSS, JavaScript, GraphQL, form data, multipart, SQL, CSV) and renders a re-formatted, word-wrapped, syntax-highlighted message that never overlaps or corrupts line heights, even for minified single-line payloads and React/Next.js hydration blobs. Payloads over 5 MB are shown unformatted rather than after a long stall."

repositories { mavenCentral() }

dependencies {
    // Vendored so the packaged add-on can shade them; both match the versions the add-on shipped with.
    implementation(files("lib/jsoup-1.17.2.jar"))
    implementation(files("lib/gson-2.11.0.jar"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

zapAddOn {
    addOnName.set("Pretty View")
    addOnStatus.set(AddOnStatus.ALPHA)
    zapVersion.set("2.17.0")
    manifest {
        author.set("ZAProxy Lab")
        url.set("https://github.com/zaproxy")
        bundle {
            baseName.set("org.zaproxy.zap.extension.prettyview.resources.Messages")
            prefix.set("prettyview")
        }
    }
}
