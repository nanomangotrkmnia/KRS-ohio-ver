package com.instrumentalist.krs.utils.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_API;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetCurrentContext;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;

/**
 * Keeps the legacy NanoVG and ImGui OpenGL renderers usable when Minecraft is
 * running on a non-OpenGL graphics backend.
 *
 * <p>The legacy renderers draw into a transparent framebuffer owned by a small
 * hidden OpenGL context. The pixels are then uploaded through Minecraft's
 * backend-neutral GPU API and composited over the active render target. No
 * OpenGL calls are made against Minecraft's Vulkan window.</p>
 */
public final class GraphicsApiCompatibility {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float MAX_SCALED_LAYER_PIXEL_RATIO = 2.0f;
    private static final long CONTINUOUS_LAYER_TIMEOUT_NANOS = 100_000_000L;

    public enum Layer {
        NANO_VG(true, 60),
        NANO_VG_BEFORE_GUI(true, 60),
        NANO_VG_IMMEDIATE(true, 60),
        ESP(true, 60),
        IMGUI(false, 0);

        private final boolean scaled;
        private final long updateIntervalNanos;

        Layer(boolean scaled, int maximumFramesPerSecond) {
            this.scaled = scaled;
            this.updateIntervalNanos = maximumFramesPerSecond <= 0
                    ? 0L
                    : 1_000_000_000L / maximumFramesPerSecond;
        }
    }

    private static final RenderPipeline COMPOSITE_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("krs", "pipeline/legacy_ui_composite"))
            .withVertexShader("core/screenquad")
            .withFragmentShader("core/blit_screen")
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    private static boolean initialized;
    private static boolean compatibilityRenderer;
    private static boolean layerActive;
    private static int activeLayerWidth;
    private static int activeLayerHeight;

    private static long offscreenWindow;
    private static GLCapabilities offscreenCapabilities;
    private static final OpenGlFramebuffer SCALED_FRAMEBUFFER = new OpenGlFramebuffer();
    private static final OpenGlFramebuffer NATIVE_FRAMEBUFFER = new OpenGlFramebuffer();
    private static final Map<Layer, LayerCache> LAYER_CACHES = new EnumMap<>(Layer.class);

    private GraphicsApiCompatibility() {
    }

    public static void initialize() {
        if (initialized)
            return;

        GpuDevice device = RenderSystem.getDevice();
        compatibilityRenderer = !(device.backend instanceof GlDevice);
        if (compatibilityRenderer) {
            createOffscreenContext();
            LOGGER.info("Krs legacy UI compatibility renderer enabled for graphics backend {}",
                    device.getDeviceInfo().backendName());
        }

        initialized = true;
    }

    public static boolean usesCompatibilityRenderer() {
        return initialized && compatibilityRenderer;
    }

    public static boolean isLayerActive() {
        return layerActive;
    }

    public static int getActiveLayerWidth() {
        return activeLayerWidth;
    }

    public static int getActiveLayerHeight() {
        return activeLayerHeight;
    }

    public static void runWithOpenGlContext(Runnable action) {
        if (action == null)
            return;
        if (!usesCompatibilityRenderer()) {
            action.run();
            return;
        }
        if (offscreenWindow == 0L || offscreenCapabilities == null)
            throw new IllegalStateException("The Krs OpenGL compatibility context is not available");

        long previousContext = glfwGetCurrentContext();
        if (previousContext == offscreenWindow) {
            action.run();
            return;
        }

        GLCapabilities previousCapabilities = currentCapabilitiesOrNull();
        glfwMakeContextCurrent(offscreenWindow);
        GL.setCapabilities(offscreenCapabilities);
        try {
            action.run();
        } finally {
            glfwMakeContextCurrent(previousContext);
            GL.setCapabilities(previousCapabilities);
        }
    }

    /**
     * Renders one transparent legacy UI layer and composites it over the
     * current Minecraft main target. Calls may be nested by immediate-mode
     * helpers without clearing the layer again.
     */
    public static void renderOffscreenLayer(Runnable renderer) {
        renderOffscreenLayer(Layer.NANO_VG_IMMEDIATE, renderer, null);
    }

    public static void renderOffscreenLayer(Layer layer, Runnable renderer) {
        renderOffscreenLayer(layer, renderer, null);
    }

    /**
     * Renders a cached compatibility layer. NanoVG-oriented layers are updated
     * at most 60 times per second and at a maximum device-pixel ratio of two.
     * Frames between updates reuse the already uploaded Vulkan texture.
     *
     * @param skipped invoked instead of {@code renderer} when a cached frame is
     *                reused; queue-backed renderers use this to drop stale work
     */
    public static void renderOffscreenLayer(Layer layer, Runnable renderer, Runnable skipped) {
        if (renderer == null)
            return;
        if (!usesCompatibilityRenderer()) {
            renderer.run();
            return;
        }
        if (layerActive) {
            renderer.run();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null)
            return;

        RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
        if (target == null || target.width <= 0 || target.height <= 0
                || target.getColorTextureView() == null)
            return;

        int[] renderSize = getRenderSize(layer, target.width, target.height);
        int width = renderSize[0];
        int height = renderSize[1];
        LayerCache cache = LAYER_CACHES.computeIfAbsent(layer, ignored -> new LayerCache());
        long now = System.nanoTime();
        boolean resumed = cache.lastInvocationNanos == 0L
                || now - cache.lastInvocationNanos > CONTINUOUS_LAYER_TIMEOUT_NANOS;
        cache.lastInvocationNanos = now;

        boolean sizeChanged = cache.width != width || cache.height != height;
        boolean update = layer.updateIntervalNanos == 0L
                || cache.texture == null
                || cache.texture.isClosed()
                || sizeChanged
                || resumed
                || now - cache.lastUploadNanos >= layer.updateIntervalNanos;

        OpenGlFramebuffer glFramebuffer = layer.scaled ? SCALED_FRAMEBUFFER : NATIVE_FRAMEBUFFER;
        if (update) {
            runWithOpenGlContext(() -> renderAndReadBack(glFramebuffer, width, height, renderer));
            cache.lastUploadNanos = now;
        } else if (skipped != null) {
            skipped.run();
        }

        uploadAndComposite(target, cache, glFramebuffer, width, height, update);
    }

    private static int[] getRenderSize(Layer layer, int targetWidth, int targetHeight) {
        if (!layer.scaled)
            return new int[]{targetWidth, targetHeight};

        Minecraft minecraft = Minecraft.getInstance();
        int logicalWidth = Math.max(1, minecraft.getWindow().getScreenWidth());
        int logicalHeight = Math.max(1, minecraft.getWindow().getScreenHeight());
        float pixelRatio = Math.max(
                targetWidth / (float) logicalWidth,
                targetHeight / (float) logicalHeight
        );
        float scale = Math.min(1.0f, MAX_SCALED_LAYER_PIXEL_RATIO / Math.max(1.0f, pixelRatio));
        return new int[]{
                Math.max(1, Math.round(targetWidth * scale)),
                Math.max(1, Math.round(targetHeight * scale))
        };
    }

    private static void createOffscreenContext() {
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        try {
            offscreenWindow = glfwCreateWindow(1, 1, "Krs OpenGL compatibility renderer", 0L, 0L);
        } finally {
            // Window hints are process-global. Do not leak the compatibility
            // context settings into windows created by Minecraft or other mods.
            glfwDefaultWindowHints();
        }

        if (offscreenWindow == 0L)
            throw new IllegalStateException("Could not create the Krs OpenGL compatibility context");

        long previousContext = glfwGetCurrentContext();
        GLCapabilities previousCapabilities = currentCapabilitiesOrNull();
        glfwMakeContextCurrent(offscreenWindow);
        try {
            offscreenCapabilities = GL.createCapabilities();
            glfwSwapInterval(0);
        } catch (RuntimeException | Error failure) {
            glfwMakeContextCurrent(previousContext);
            GL.setCapabilities(previousCapabilities);
            glfwDestroyWindow(offscreenWindow);
            offscreenWindow = 0L;
            throw failure;
        }

        glfwMakeContextCurrent(previousContext);
        GL.setCapabilities(previousCapabilities);
    }

    private static GLCapabilities currentCapabilitiesOrNull() {
        try {
            return GL.getCapabilities();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static void renderAndReadBack(OpenGlFramebuffer target, int width, int height, Runnable renderer) {
        ensureOpenGlFramebuffer(target, width, height);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
        try {
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glStencilMask(0xFF);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClearDepth(1.0);
            GL11.glClearStencil(0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);

            layerActive = true;
            activeLayerWidth = width;
            activeLayerHeight = height;
            try {
                renderer.run();
            } finally {
                layerActive = false;
                activeLayerWidth = 0;
                activeLayerHeight = 0;
            }

            int packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            int packRowLength = GL11.glGetInteger(GL12.GL_PACK_ROW_LENGTH);
            int pixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, 0);
                target.readbackBuffer.clear();
                GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, target.readbackBuffer);
                target.readbackBuffer.position(0);
                target.readbackBuffer.limit(Math.multiplyExact(Math.multiplyExact(width, height), 4));
            } finally {
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment);
                GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, packRowLength);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pixelPackBuffer);
            }
        } finally {
            layerActive = false;
            activeLayerWidth = 0;
            activeLayerHeight = 0;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }

    private static void ensureOpenGlFramebuffer(OpenGlFramebuffer target, int width, int height) {
        if (target.framebuffer != 0 && target.width == width && target.height == height)
            return;

        destroyOpenGlFramebuffer(target);

        target.framebuffer = GL30.glGenFramebuffers();
        target.colorTexture = GL11.glGenTextures();
        target.depthStencilRenderbuffer = GL30.glGenRenderbuffers();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.colorTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, target.colorTexture, 0);

        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, target.depthStencilRenderbuffer);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                GL30.GL_RENDERBUFFER, target.depthStencilRenderbuffer);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            destroyOpenGlFramebuffer(target);
            throw new IllegalStateException("Incomplete Krs compatibility framebuffer: 0x"
                    + Integer.toHexString(status));
        }

        target.width = width;
        target.height = height;
        int requiredBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        target.readbackBuffer = MemoryUtil.memAlloc(requiredBytes);
    }

    private static void uploadAndComposite(RenderTarget target, LayerCache cache, OpenGlFramebuffer glFramebuffer,
                                           int width, int height, boolean upload) {
        ensureUploadTexture(cache, width, height);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (upload)
            encoder.writeToTexture(cache.texture, glFramebuffer.readbackBuffer, 0, 0, 0, 0, width, height);

        GpuTextureView depthView = target.getDepthTextureView();
        try (RenderPass pass = depthView == null
                ? encoder.createRenderPass(() -> "Krs legacy UI composite", target.getColorTextureView(), Optional.empty())
                : encoder.createRenderPass(() -> "Krs legacy UI composite", target.getColorTextureView(), Optional.empty(),
                        depthView, OptionalDouble.empty())) {
            pass.setPipeline(COMPOSITE_PIPELINE);
            pass.bindTexture("InSampler", cache.textureView,
                    RenderSystem.getSamplerCache().getClampToEdge(
                            width == target.width && height == target.height ? FilterMode.NEAREST : FilterMode.LINEAR
                    ));
            pass.draw(3, 1, 0, 0);
        }
    }

    private static void ensureUploadTexture(LayerCache cache, int width, int height) {
        if (cache.texture != null && !cache.texture.isClosed()
                && cache.width == width && cache.height == height)
            return;

        destroyUploadTexture(cache);
        GpuDevice device = RenderSystem.getDevice();
        cache.texture = device.createTexture(
                "Krs legacy UI upload",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1
        );
        cache.textureView = device.createTextureView(cache.texture);
        cache.width = width;
        cache.height = height;
    }

    public static void shutdown() {
        if (!initialized)
            return;

        for (LayerCache cache : LAYER_CACHES.values())
            destroyUploadTexture(cache);
        LAYER_CACHES.clear();

        if (compatibilityRenderer && offscreenWindow != 0L) {
            runWithOpenGlContext(() -> {
                destroyOpenGlFramebuffer(SCALED_FRAMEBUFFER);
                destroyOpenGlFramebuffer(NATIVE_FRAMEBUFFER);
            });
            glfwDestroyWindow(offscreenWindow);
        }

        offscreenWindow = 0L;
        offscreenCapabilities = null;
        compatibilityRenderer = false;
        layerActive = false;
        activeLayerWidth = 0;
        activeLayerHeight = 0;
        initialized = false;
    }

    private static void destroyOpenGlFramebuffer(OpenGlFramebuffer target) {
        if (target.depthStencilRenderbuffer != 0) {
            GL30.glDeleteRenderbuffers(target.depthStencilRenderbuffer);
            target.depthStencilRenderbuffer = 0;
        }
        if (target.colorTexture != 0) {
            GL11.glDeleteTextures(target.colorTexture);
            target.colorTexture = 0;
        }
        if (target.framebuffer != 0) {
            GL30.glDeleteFramebuffers(target.framebuffer);
            target.framebuffer = 0;
        }
        if (target.readbackBuffer != null) {
            MemoryUtil.memFree(target.readbackBuffer);
            target.readbackBuffer = null;
        }
        target.width = 0;
        target.height = 0;
    }

    private static void destroyUploadTexture(LayerCache cache) {
        if (cache.textureView != null) {
            cache.textureView.close();
            cache.textureView = null;
        }
        if (cache.texture != null) {
            cache.texture.close();
            cache.texture = null;
        }
        cache.width = 0;
        cache.height = 0;
    }

    private static final class OpenGlFramebuffer {
        private int framebuffer;
        private int colorTexture;
        private int depthStencilRenderbuffer;
        private int width;
        private int height;
        private ByteBuffer readbackBuffer;
    }

    private static final class LayerCache {
        private GpuTexture texture;
        private GpuTextureView textureView;
        private int width;
        private int height;
        private long lastUploadNanos;
        private long lastInvocationNanos;
    }
}
