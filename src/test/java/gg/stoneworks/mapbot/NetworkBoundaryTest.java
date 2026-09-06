package gg.stoneworks.mapbot;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the claim the bot's sanctioned access depends on: it reads a public web map and talks to
 * Discord, and it cannot reach the game server.
 *
 * <p>Asserted as a build failure rather than left to code review because the claim is made to
 * server staff, and a reviewer who misses one import in one pull request silently invalidates it. A
 * staff member auditing this project should be able to read {@code net/README.md} plus this file
 * and be satisfied without reading the rest of the tree.
 *
 * <p>Adding a package to the exemption list is a change to what the project promises to staff. It
 * is not a test fix.
 */
@AnalyzeClasses(packages = "gg.stoneworks.mapbot",
        importOptions = ImportOption.DoNotIncludeTests.class)
class NetworkBoundaryTest {

    /**
     * JDK types that can actually move bytes over a network.
     *
     * <p>Deliberately excludes {@code java.net.URI} and {@code java.net.URLEncoder}: those are
     * inert value and encoding types that any layer may legitimately use to build a link for a
     * Discord embed. Banning them would produce failures that are not violations, and a rule that
     * cries wolf gets weakened by the next person who hits it.
     *
     * <p>JDA needs no exemption. Application code depends on {@code net.dv8tion.jda}, which this
     * pattern does not match, so JDA opening its own sockets to Discord is out of scope by
     * construction rather than by a carve-out that could be widened later.
     */
    private static final String NETWORK_CAPABLE_JDK_TYPES =
            "java\\.net\\.http\\..*"
                    + "|java\\.net\\.(Socket|ServerSocket|DatagramSocket|MulticastSocket"
                    + "|URL|URLConnection|HttpURLConnection|JarURLConnection)";

    /**
     * The boundary itself.
     *
     * <p>{@code allowEmptyShould} is on because the rule legitimately matches nothing while
     * {@code net} is the only package in the tree, and a green build should not depend on how much
     * of the bot has been written yet. That concession removes ArchUnit's built-in protection
     * against a rule that silently checks nothing, which
     * {@link #theNetPackageIsActuallyBeingAnalysed} restores.
     */
    @ArchTest
    static final ArchRule onlyTheNetPackageTalksToTheNetwork = noClasses()
            .that().resideOutsideOfPackage("..mapbot.net..")
            .should().dependOnClassesThat().haveNameMatching(NETWORK_CAPABLE_JDK_TYPES)
            .because("net/ is the only package permitted to open a socket; see "
                    + "src/main/java/gg/stoneworks/mapbot/net/README.md")
            .allowEmptyShould(true);

    /**
     * Canary proving the analysis can see our classes at all.
     *
     * <p>The assertion is trivially true by construction. That is the point: ArchUnit fails a rule
     * matching no classes, so this fails loudly if {@code net} is renamed, moved, or excluded from
     * the import, the one scenario where the rule above would pass while checking nothing.
     */
    @ArchTest
    static final ArchRule theNetPackageIsActuallyBeingAnalysed = classes()
            .that().resideInAPackage("..mapbot.net..")
            .should().haveNameMatching("gg\\.stoneworks\\.mapbot\\.net\\..*")
            .because("a boundary rule that matches no classes proves nothing");
}
