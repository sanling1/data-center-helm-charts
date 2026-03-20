package test.postinstall;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import test.model.Product;

import static io.restassured.RestAssured.when;
import static java.lang.Thread.sleep;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static test.postinstall.Utils.*;

@EnabledIf("isOSDeployed")
class OpenSearchInstallTest {
    static boolean isOSDeployed() {
        return productIs(Product.bitbucket) || productIs(Product.jira);
    }

    private static KubeClient client;
    private static String osIngressBase;

    @BeforeAll
    static void initKubeClient() {
        client = new KubeClient();

        // See helm_install.sh for where this host is generated.
        final var ingressDomain = getIngressDomain(client.getClusterType());
        osIngressBase = "https://" + getRelease() + "-opensearch-cluster-master-0." + ingressDomain;
    }

    @Test
    void openSearchIsRunning() {
        var osSetName = "opensearch-cluster-master";
        client.forEachPodOfStatefulSet(osSetName, pod -> {
            final var podPhase = pod.getStatus().getPhase();
            assertThat(podPhase)
                    .describedAs("Pod %s should be running", pod.getMetadata().getName())
                    .isEqualToIgnoringCase("Running");
        });
    }

    @Test
    void openSearchBeingUsed() throws InterruptedException {
        int retries = 120; // It might take a little while to propagate.
        while (retries > 0) {
            try {
                // Relies on the backdoor ingress controller installed by helm_install.sh.
                // If this changes an alternative would be to use the fabric8 client ExecWatch/ExecListener to
                // invoke curl from a pod.
                final var indexURL = osIngressBase + "/_cat/indices?format=json";

                if (productIs(Product.bitbucket)) {
                    when().get(indexURL).then()
                            .body("findAll { it.index == 'bitbucket-index-version' }[0]", hasEntry("docs.count", "1"));

                } else if (productIs(Product.jira)) {
                    // expecting issues index like jira-issues-20260320135736
                    when().get(indexURL).then()
                            .body("findAll { it.index =~ /jira-issues-.*/ }[0]", hasEntry("docs.count", not(equalTo("0"))));

                } else {
                    fail("Unknown product: " + getProduct());
                }

                // test passed
                return;
            } catch (Exception e) {
                retries--;
                if (retries <= 0) {
                    fail("Open search did not become ready in time.", e);
                }
                sleep(1000);
            }
        }
    }

    @AfterAll
    static void disposeOfClient() {
        client.close();
    }
}
