package tech.jojidev.postbox;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class NotificationArchitectureTests {
    private final com.tngtech.archunit.core.domain.JavaClasses classes =
            new ClassFileImporter().importPackages("tech.jojidev.postbox");

    @Test
    void channelServicesDoNotDependOnHttpContract() {
        noClasses().that().resideInAnyPackage("tech.jojidev.postbox.email..", "tech.jojidev.postbox.push..")
                .should().dependOnClassesThat().resideInAPackage("tech.jojidev.postbox.api..")
                .check(classes);
    }

    @Test
    void persistenceDoesNotDependOnWorkersOrTransports() {
        noClasses().that().resideInAPackage("tech.jojidev.postbox.push.persistence..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "tech.jojidev.postbox.push.worker..", "tech.jojidev.postbox.push.messaging..", "tech.jojidev.postbox.push.expo..")
                .check(classes);
    }

    @Test
    void emailAndPushRemainIndependent() {
        noClasses().that().resideInAPackage("tech.jojidev.postbox.email..")
                .should().dependOnClassesThat().resideInAPackage("tech.jojidev.postbox.push..")
                .check(classes);
    }
}
