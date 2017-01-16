package de.zalando.tip.zalenium.proxy;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.Container;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.Image;
import de.zalando.tip.zalenium.util.CommonProxyUtilities;
import de.zalando.tip.zalenium.util.Environment;
import de.zalando.tip.zalenium.util.GoogleAnalyticsApi;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.listeners.RegistrationListener;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;
import org.openqa.selenium.Platform;
import org.openqa.selenium.remote.BrowserType;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The idea of this proxy instance is:
 * 1. Receive a session request with some requested capabilities
 * 2. Start a docker-selenium container that will register with the hub
 * 3. Reject the received request
 * 4. When the registry receives the rejected request and sees the new registered node from step 2,
 * the process will flow as normal.
 */

public class DockerSeleniumStarterRemoteProxy extends DefaultRemoteProxy implements RegistrationListener {

    @VisibleForTesting
    static final int DEFAULT_AMOUNT_CHROME_CONTAINERS = 0;
    @VisibleForTesting
    static final int DEFAULT_AMOUNT_FIREFOX_CONTAINERS = 0;
    @VisibleForTesting
    static final int DEFAULT_AMOUNT_DOCKER_SELENIUM_CONTAINERS_RUNNING = 10;
    @VisibleForTesting
    static final String DEFAULT_TZ = "Europe/Berlin";
    @VisibleForTesting
    static final int DEFAULT_SCREEN_WIDTH = 1900;
    @VisibleForTesting
    static final int DEFAULT_SCREEN_HEIGHT = 1880;
    @VisibleForTesting
    static final String ZALENIUM_CHROME_CONTAINERS = "ZALENIUM_CHROME_CONTAINERS";
    @VisibleForTesting
    static final String DEFAULT_CONTAINER_ID = "zalenium";
    @VisibleForTesting
    static final String ZALENIUM_FIREFOX_CONTAINERS = "ZALENIUM_FIREFOX_CONTAINERS";
    @VisibleForTesting
    static final String ZALENIUM_MAX_DOCKER_SELENIUM_CONTAINERS = "ZALENIUM_MAX_DOCKER_SELENIUM_CONTAINERS";
    @VisibleForTesting
    static final String ZALENIUM_TZ = "ZALENIUM_TZ";
    @VisibleForTesting
    static final String ZALENIUM_SCREEN_WIDTH = "ZALENIUM_SCREEN_WIDTH";
    @VisibleForTesting
    static final String ZALENIUM_SCREEN_HEIGHT = "ZALENIUM_SCREEN_HEIGHT";
    @VisibleForTesting
    static final String CONTAINER_ID = "HOSTNAME";
    @VisibleForTesting
    static final String DOCKER_SELENIUM_CAPABILITIES_URL =
            "https://raw.githubusercontent.com/elgalu/docker-selenium/latest/capabilities.json";
    private static final Logger LOGGER = Logger.getLogger(DockerSeleniumStarterRemoteProxy.class.getName());
    private static final String DOCKER_SELENIUM_IMAGE = "elgalu/selenium";
    private static final int LOWER_PORT_BOUNDARY = 40000;
    private static final int UPPER_PORT_BOUNDARY = 49999;
    private static final DockerClient defaultDockerClient = new DefaultDockerClient("unix:///var/run/docker.sock");
    private static final Environment defaultEnvironment = new Environment();
    private static final CommonProxyUtilities defaultCommonProxyUtilities = new CommonProxyUtilities();
    private static final String LOGGING_PREFIX = "[DS] ";
    private static List<DesiredCapabilities> dockerSeleniumCapabilities = new ArrayList<>();
    private static DockerClient dockerClient = defaultDockerClient;
    private static Environment env = defaultEnvironment;
    private static CommonProxyUtilities commonProxyUtilities = defaultCommonProxyUtilities;
    private static GoogleAnalyticsApi ga = new GoogleAnalyticsApi();
    private static int chromeContainersOnStartup;
    private static int firefoxContainersOnStartup;
    private static int maxDockerSeleniumContainers;
    private static String timeZone;
    private static int screenWidth;
    private static int screenHeight;
    private List<Integer> allocatedPorts = new ArrayList<>();
    private boolean setupCompleted;
    private static String containerId;

    @SuppressWarnings("WeakerAccess")
    public DockerSeleniumStarterRemoteProxy(RegistrationRequest request, Registry registry) {
        super(updateDSCapabilities(request, DOCKER_SELENIUM_CAPABILITIES_URL), registry);
    }

    /*
     * Reading configuration values from the env variables, if a value was not provided it falls back to defaults.
     */
    private static void readConfigurationFromEnvVariables() {

        int chromeContainers = env.getIntEnvVariable(ZALENIUM_CHROME_CONTAINERS, DEFAULT_AMOUNT_CHROME_CONTAINERS);
        setChromeContainersOnStartup(chromeContainers);

        int firefoxContainers = env.getIntEnvVariable(ZALENIUM_FIREFOX_CONTAINERS, DEFAULT_AMOUNT_FIREFOX_CONTAINERS);
        setFirefoxContainersOnStartup(firefoxContainers);

        int maxDSContainers = env.getIntEnvVariable(ZALENIUM_MAX_DOCKER_SELENIUM_CONTAINERS,
                DEFAULT_AMOUNT_DOCKER_SELENIUM_CONTAINERS_RUNNING);
        setMaxDockerSeleniumContainers(maxDSContainers);

        int sWidth = env.getIntEnvVariable(ZALENIUM_SCREEN_WIDTH, DEFAULT_SCREEN_WIDTH);
        setScreenWidth(sWidth);

        int sHeight = env.getIntEnvVariable(ZALENIUM_SCREEN_HEIGHT, DEFAULT_SCREEN_HEIGHT);
        setScreenHeight(sHeight);

        String tz = env.getStringEnvVariable(ZALENIUM_TZ, DEFAULT_TZ);
        setTimeZone(tz);

        String cid = env.getStringEnvVariable(CONTAINER_ID, DEFAULT_CONTAINER_ID);
        setContainerId(cid);
    }

    /*
     *  Updating the proxy's registration request information with the current DockerSelenium capabilities.
     *  If it is not possible to retrieve them, then we default to Chrome and Firefox in Linux.
     */
    @VisibleForTesting
    static RegistrationRequest updateDSCapabilities(RegistrationRequest registrationRequest, String url) {
        registrationRequest.getCapabilities().clear();
        registrationRequest.getCapabilities().addAll(getCapabilities(url));
        return registrationRequest;
    }

    @VisibleForTesting
    static void clearCapabilities() {
        dockerSeleniumCapabilities.clear();
    }

    private static List<DesiredCapabilities> getCapabilities(String url) {
        if (!dockerSeleniumCapabilities.isEmpty()) {
            return dockerSeleniumCapabilities;
        }

        dockerSeleniumCapabilities = getDockerSeleniumCapabilitiesFromGitHub(url);
        if (dockerSeleniumCapabilities.isEmpty()) {
            dockerSeleniumCapabilities = getDockerSeleniumFallbackCapabilities();
            LOGGER.log(Level.WARNING, LOGGING_PREFIX + "Could not fetch capabilities from {0}, falling back to defaults.", url);
            return dockerSeleniumCapabilities;
        }
        LOGGER.log(Level.INFO, LOGGING_PREFIX + "Capabilities fetched from {0}", url);
        return dockerSeleniumCapabilities;
    }

    @VisibleForTesting
    static void setDockerClient(final DockerClient client) {
        dockerClient = client;
    }

    @VisibleForTesting
    static void restoreDockerClient() {
        dockerClient = defaultDockerClient;
    }

    @VisibleForTesting
    static void setCommonProxyUtilities(final CommonProxyUtilities utilities) {
        commonProxyUtilities = utilities;
    }

    @VisibleForTesting
    static void restoreCommonProxyUtilities() {
        commonProxyUtilities = defaultCommonProxyUtilities;
    }

    static int getFirefoxContainersOnStartup() {
        return firefoxContainersOnStartup;
    }

    static void setFirefoxContainersOnStartup(int firefoxContainersOnStartup) {
        DockerSeleniumStarterRemoteProxy.firefoxContainersOnStartup = firefoxContainersOnStartup < 0 ?
                DEFAULT_AMOUNT_FIREFOX_CONTAINERS : firefoxContainersOnStartup;
    }

    static int getChromeContainersOnStartup() {
        return chromeContainersOnStartup;
    }

    static void setChromeContainersOnStartup(int chromeContainersOnStartup) {
        DockerSeleniumStarterRemoteProxy.chromeContainersOnStartup = chromeContainersOnStartup < 0 ?
                DEFAULT_AMOUNT_CHROME_CONTAINERS : chromeContainersOnStartup;
    }

    static int getMaxDockerSeleniumContainers() {
        return maxDockerSeleniumContainers;
    }

    static void setMaxDockerSeleniumContainers(int maxDockerSeleniumContainers) {
        DockerSeleniumStarterRemoteProxy.maxDockerSeleniumContainers = maxDockerSeleniumContainers < 0 ?
                DEFAULT_AMOUNT_DOCKER_SELENIUM_CONTAINERS_RUNNING : maxDockerSeleniumContainers;
    }

    static String getTimeZone() {
        return timeZone;
    }

    static void setTimeZone(String timeZone) {
        if (!Arrays.asList(TimeZone.getAvailableIDs()).contains(timeZone)) {
            LOGGER.log(Level.WARNING, String.format("%s is not a real time zone.", timeZone));
        }
        DockerSeleniumStarterRemoteProxy.timeZone = Arrays.asList(TimeZone.getAvailableIDs()).contains(timeZone) ?
                timeZone : DEFAULT_TZ;
    }

    static int getScreenWidth() {
        return screenWidth;
    }

    static void setScreenWidth(int screenWidth) {
        DockerSeleniumStarterRemoteProxy.screenWidth = screenWidth <= 0 ? DEFAULT_SCREEN_WIDTH : screenWidth;
    }

    static int getScreenHeight() {
        return screenHeight;
    }

    static void setScreenHeight(int screenHeight) {
        DockerSeleniumStarterRemoteProxy.screenHeight = screenHeight <= 0 ? DEFAULT_SCREEN_HEIGHT : screenHeight;
    }

    @VisibleForTesting
    protected static void setEnv(final Environment env) {
        DockerSeleniumStarterRemoteProxy.env = env;
    }

    static void setContainerId(String containerId) {
        DockerSeleniumStarterRemoteProxy.containerId = containerId;
    }

    static String getContainerId() {
        return containerId;
    }

    @VisibleForTesting
    static void restoreEnvironment() {
        env = defaultEnvironment;
    }

    private static List<DesiredCapabilities> getDockerSeleniumCapabilitiesFromGitHub(String url) {
        JsonElement dsCapabilities = commonProxyUtilities.readJSONFromUrl(url);
        List<DesiredCapabilities> desiredCapabilitiesArrayList = new ArrayList<>();
        try {
            if (dsCapabilities != null) {
                for (JsonElement cap : dsCapabilities.getAsJsonObject().getAsJsonArray("caps")) {
                    JsonObject capAsJsonObject = cap.getAsJsonObject();
                    DesiredCapabilities desiredCapabilities = new DesiredCapabilities();
                    desiredCapabilities.setCapability(RegistrationRequest.MAX_INSTANCES, 1);
                    desiredCapabilities.setBrowserName(capAsJsonObject.get("BROWSER_NAME").getAsString());
                    desiredCapabilities.setPlatform(Platform.fromString(capAsJsonObject.get("PLATFORM").getAsString()));
                    desiredCapabilities.setVersion(capAsJsonObject.get("VERSION").getAsString());
                    desiredCapabilitiesArrayList.add(desiredCapabilities);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOGGING_PREFIX + e.toString(), e);
            ga.trackException(e);

        }
        return desiredCapabilitiesArrayList;
    }

    @VisibleForTesting
    public static List<DesiredCapabilities> getDockerSeleniumFallbackCapabilities() {
        List<DesiredCapabilities> dsCapabilities = new ArrayList<>();
        DesiredCapabilities desiredCapabilities = new DesiredCapabilities();
        desiredCapabilities.setBrowserName(BrowserType.FIREFOX);
        desiredCapabilities.setPlatform(Platform.LINUX);
        desiredCapabilities.setCapability(RegistrationRequest.MAX_INSTANCES, 1);
        dsCapabilities.add(desiredCapabilities);
        desiredCapabilities = new DesiredCapabilities();
        desiredCapabilities.setBrowserName(BrowserType.CHROME);
        desiredCapabilities.setPlatform(Platform.LINUX);
        desiredCapabilities.setCapability(RegistrationRequest.MAX_INSTANCES, 1);
        dsCapabilities.add(desiredCapabilities);
        return dsCapabilities;
    }

    /**
     * Receives a request to create a new session, but instead of accepting it, it will create a
     * docker-selenium container which will register to the hub, then reject the request and the hub
     * will assign the request to the new registered node.
     */
    @Override
    public TestSession getNewSession(Map<String, Object> requestedCapability) {

        if (!hasCapability(requestedCapability)) {
            LOGGER.log(Level.FINE, LOGGING_PREFIX + "Capability not supported {0}", requestedCapability);
            return null;
        }

        if (!requestedCapability.containsKey(CapabilityType.BROWSER_NAME)) {
            LOGGER.log(Level.INFO, String.format("%s Capability %s does no contain %s key.", LOGGING_PREFIX,
                    requestedCapability, CapabilityType.BROWSER_NAME));
            return null;
        }

        LOGGER.log(Level.INFO, LOGGING_PREFIX + "Starting new node for {0}.", requestedCapability);

        String browserName = requestedCapability.get(CapabilityType.BROWSER_NAME).toString();

        /*
            Here a docker-selenium container will be started and it will register to the hub
         */
        startDockerSeleniumContainer(browserName);
        return null;
    }

    /*
        Starting a few containers (Firefox, Chrome), so they are ready when the tests come.
        Executed in a thread so we don't wait for the containers to be created and the node
        registration is not delayed.
    */
    @Override
    public void beforeRegistration() {
        readConfigurationFromEnvVariables();
        setupCompleted = false;
        createStartupContainers();
    }

    /*
        Making the node seem as heavily used, in order to get it listed after the 'docker-selenium' nodes.
        98% used.
     */
    @Override
    public float getResourceUsageInPercent() {
        return 98;
    }

    @VisibleForTesting
    boolean startDockerSeleniumContainer(String browser) {

        if (validateAmountOfDockerSeleniumContainers()) {

            String hostIpAddress = "localhost";

            /*
                Building the docker command, depending if Chrome or Firefox is requested.
                To launch only the requested node type.
             */

            final int nodePort = findFreePortInRange(LOWER_PORT_BOUNDARY, UPPER_PORT_BOUNDARY);
            final int vncPort = nodePort + 10000;

            List<String> envVariables = new ArrayList<>();
            envVariables.add("SELENIUM_HUB_HOST=" + hostIpAddress);
            envVariables.add("SELENIUM_HUB_PORT=4445");
            envVariables.add("SELENIUM_NODE_HOST=" + hostIpAddress);
            envVariables.add("GRID=false");
            envVariables.add("RC_CHROME=false");
            envVariables.add("RC_FIREFOX=false");
            envVariables.add("WAIT_TIMEOUT=120s");
            envVariables.add("PICK_ALL_RANDMON_PORTS=true");
            envVariables.add("PICK_ALL_RANDOM_PORTS=true");
            envVariables.add("VIDEO_STOP_SLEEP_SECS=6");
            envVariables.add("WAIT_TIME_OUT_VIDEO_STOP=20s");
            boolean sendAnonymousUsageInfo = env.getBooleanEnvVariable("ZALENIUM_SEND_ANONYMOUS_USAGE_INFO", false);
            envVariables.add("SEND_ANONYMOUS_USAGE_INFO=" + sendAnonymousUsageInfo);
            envVariables.add("BUILD_URL=" + env.getStringEnvVariable("BUILD_URL", ""));
            envVariables.add("NOVNC=true");
            envVariables.add("NOVNC_PORT=" + vncPort);
            envVariables.add("SCREEN_WIDTH=" + getScreenWidth());
            envVariables.add("SCREEN_HEIGHT=" + getScreenHeight());
            envVariables.add("TZ=" + getTimeZone());
            envVariables.add("SELENIUM_NODE_REGISTER_CYCLE=0");
            envVariables.add("SELENIUM_NODE_PROXY_PARAMS=de.zalando.tip.zalenium.proxy.DockerSeleniumRemoteProxy");
            if (BrowserType.CHROME.equalsIgnoreCase(browser)) {
                envVariables.add("SELENIUM_NODE_CH_PORT=" + nodePort);
                envVariables.add("CHROME=true");
            } else {
                envVariables.add("CHROME=false");
            }
            if (BrowserType.FIREFOX.equalsIgnoreCase(browser)) {
                envVariables.add("SELENIUM_NODE_FF_PORT=" + nodePort);
                envVariables.add("FIREFOX=true");
            } else {
                envVariables.add("FIREFOX=false");
            }

            HostConfig hostConfig = HostConfig.builder()
                    .shmSize(1073741824L) // 1GB
                    .networkMode(String.format("container:%s", containerId))
                    .autoRemove(true)
                    .build();

            try {
                final ContainerConfig containerConfig = ContainerConfig.builder()
                        .image(getLatestDownloadedImage(DOCKER_SELENIUM_IMAGE))
                        .env(envVariables)
                        .hostConfig(hostConfig)
                        .build();

                String containerName = String.format("zalenium_%s_%s", containerId, nodePort);
                final ContainerCreation dockerSeleniumContainer = dockerClient.createContainer(containerConfig,
                        containerName);
                dockerClient.startContainer(dockerSeleniumContainer.id());
                return true;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, LOGGING_PREFIX + e.toString(), e);
                ga.trackException(e);
            }
        }
        return false;
    }

    private String getLatestDownloadedImage(String imageName) throws DockerException, InterruptedException {
        List<Image> images = dockerClient.listImages(DockerClient.ListImagesParam.byName(imageName));
        if (images.isEmpty()) {
            LOGGER.log(Level.SEVERE, "A downloaded docker-selenium image was not found!");
            return DOCKER_SELENIUM_IMAGE;
        }
        for (int i = images.size() - 1; i >= 0; i--) {
            if (images.get(i).repoTags() == null) {
                images.remove(i);
            }
        }
        images.sort((o1, o2) -> o2.created().compareTo(o1.created()));
        return images.get(0).repoTags().get(0);
    }

    boolean isSetupCompleted() {
        return setupCompleted;
    }

    private void createStartupContainers() {
        int configuredContainers = getChromeContainersOnStartup() + getFirefoxContainersOnStartup();
        int containersToCreate = configuredContainers > getMaxDockerSeleniumContainers() ?
                getMaxDockerSeleniumContainers() : configuredContainers;
        new Thread(() -> {
            LOGGER.log(Level.INFO, String.format("%s Setting up %s nodes...", LOGGING_PREFIX, configuredContainers));
            int createdContainers = 0;
            while (createdContainers < containersToCreate && getNumberOfRunningContainers() <= getMaxDockerSeleniumContainers()) {
                boolean wasContainerCreated;
                if (createdContainers < getChromeContainersOnStartup()) {
                    wasContainerCreated = startDockerSeleniumContainer(BrowserType.CHROME);
                } else {
                    wasContainerCreated = startDockerSeleniumContainer(BrowserType.FIREFOX);
                }
                createdContainers = wasContainerCreated ? createdContainers + 1 : createdContainers;
            }
            LOGGER.log(Level.INFO, String.format("%s containers were created, it will take a bit more until all get registered.", createdContainers));
            setupCompleted = true;
        }).start();
    }

    private int getNumberOfRunningContainers() {
        try {
            List<Container> containerList = dockerClient.listContainers(DockerClient.ListContainersParam.allContainers());
            int numberOfDockerSeleniumContainers = 0;
            for (Container container : containerList) {
                if (container.image().contains(DOCKER_SELENIUM_IMAGE) && !"exited".equalsIgnoreCase(container.state())) {
                    numberOfDockerSeleniumContainers++;
                }
            }
            return numberOfDockerSeleniumContainers;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, LOGGING_PREFIX + e.toString(), e);
            ga.trackException(e);
        }
        return 0;
    }

    private boolean validateAmountOfDockerSeleniumContainers() {
        try {
            int numberOfDockerSeleniumContainers = getNumberOfRunningContainers();

            /*
                Validation to avoid the situation where 20 containers are running and only 4 proxies are registered.
                The remaining 16 are not registered because they are all trying to do it and the hub just cannot
                process all the registrations fast enough, causing many unexpected errors.
            */
            int tolerableDifference = 4;
            int numberOfProxies = getRegistry().getAllProxies().size() + tolerableDifference;
            if (numberOfDockerSeleniumContainers > numberOfProxies) {
                LOGGER.log(Level.FINE, LOGGING_PREFIX + "More docker-selenium containers running than proxies, {0} vs. {1}",
                        new Object[]{numberOfDockerSeleniumContainers, numberOfProxies});
                Thread.sleep(500);
                return false;
            }

            LOGGER.log(Level.FINE, String.format("%s %s docker-selenium containers running", LOGGING_PREFIX,
                    numberOfDockerSeleniumContainers));
            if (numberOfDockerSeleniumContainers >= getMaxDockerSeleniumContainers()) {
                LOGGER.log(Level.FINE, LOGGING_PREFIX + "Max. number of docker-selenium containers has been reached, " +
                        "no more will be created until the number decreases below {0}.", getMaxDockerSeleniumContainers());
                return false;
            }
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, LOGGING_PREFIX + e.toString(), e);
            ga.trackException(e);
        }
        return false;
    }

    /*
        Method adapted from https://gist.github.com/vorburger/3429822
     */
    private int findFreePortInRange(int lowerBoundary, int upperBoundary) {
        for (int portNumber = lowerBoundary; portNumber <= upperBoundary; portNumber++) {
            if (!allocatedPorts.contains(portNumber)) {
                int freePort = -1;

                try (ServerSocket serverSocket = new ServerSocket(portNumber)) {
                    freePort = serverSocket.getLocalPort();
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, LOGGING_PREFIX + e.toString(), e);
                }

                if (freePort != -1) {
                    allocatedPorts.add(freePort);
                    return freePort;
                }
            }
        }
        return -1;
    }

}
