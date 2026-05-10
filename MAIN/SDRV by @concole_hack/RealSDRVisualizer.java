import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.concurrent.*;

public class RealSDRVisualizer extends JPanel implements Runnable {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int SAMPLE_RATE = 2400000;
    private static final int BUFFER_SIZE = 65536;
    private static final int FFT_SIZE = 4096;

    private Socket socket;
    private DataInputStream dis;
    private DataOutputStream dos;
    private volatile boolean running = true;
    private float[] iqBuffer = new float[BUFFER_SIZE * 2];
    private int bufferPos = 0;
    private float[] real = new float[FFT_SIZE];
    private float[] imag = new float[FFT_SIZE];
    private int[] spectrum = new int[FFT_SIZE / 2];
    private double centerFreq = 100000000;
    private double peakFreq = 0;
    private double avgPower = 0;
    private double noiseFloor = 0;
    private JFrame frame;
    private JTextField freqField;
    private JLabel statusLabel;
    private JLabel freqLabel;
    private JLabel powerLabel;
    private JLabel noiseLabel;
    private JSlider gainSlider;
    private JComboBox<String> deviceBox;
    private Timer updateTimer;

    public RealSDRVisualizer() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(Color.BLACK);
        createUI();
        connectToRtlTcp();
        startCapture();
        new Thread(this).start();
    }

    private void createUI() {
        frame = new JFrame("REAL SDR VISUALIZER - by @console_hack");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JPanel top = new JPanel();
        top.setBackground(new Color(10,10,10));
        top.setBorder(BorderFactory.createLineBorder(Color.GREEN, 1));

        freqField = new JTextField("100.000", 10);
        freqField.setBackground(Color.BLACK);
        freqField.setForeground(Color.GREEN);
        freqField.setFont(new Font("Monospaced", Font.BOLD, 14));

        JButton setFreq = new JButton("SET FREQ");
        setFreq.setBackground(Color.BLACK);
        setFreq.setForeground(Color.GREEN);
        setFreq.addActionListener(e -> setFrequency());

        gainSlider = new JSlider(0, 100, 40);
        gainSlider.setBackground(Color.BLACK);
        gainSlider.setForeground(Color.GREEN);

        String[] devices = {"RTL-SDR USB", "RTL-SDR v3", "RTL-SDR v4", "Auto"};
        deviceBox = new JComboBox<>(devices);
        deviceBox.setBackground(Color.BLACK);
        deviceBox.setForeground(Color.GREEN);

        statusLabel = new JLabel("STATUS: CONNECTING...");
        statusLabel.setForeground(Color.YELLOW);
        statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));

        freqLabel = new JLabel("FREQ: 100.000 MHz");
        freqLabel.setForeground(Color.CYAN);
        freqLabel.setFont(new Font("Monospaced", Font.BOLD, 12));

        powerLabel = new JLabel("PWR: -INF dBm");
        powerLabel.setForeground(Color.GREEN);

        noiseLabel = new JLabel("NOISE: -INF dB");
        noiseLabel.setForeground(Color.ORANGE);

        top.add(new JLabel("Freq (MHz):") {{ setForeground(Color.GREEN); }});
        top.add(freqField);
        top.add(setFreq);
        top.add(new JLabel(" Gain:") {{ setForeground(Color.GREEN); }});
        top.add(gainSlider);
        top.add(new JLabel(" Device:") {{ setForeground(Color.GREEN); }});
        top.add(deviceBox);
        top.add(statusLabel);
        top.add(freqLabel);
        top.add(powerLabel);
        top.add(noiseLabel);

        frame.add(top, BorderLayout.NORTH);
        frame.add(this, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void connectToRtlTcp() {
        try {
            socket = new Socket("127.0.0.1", 1234);
            dis = new DataInputStream(socket.getInputStream());
            dos = new DataOutputStream(socket.getOutputStream());

            dos.writeByte(0x01);
            dos.writeInt(0);
            dos.writeInt(2400000);
            dos.writeInt(0);
            dos.flush();

            byte[] resp = new byte[5];
            dis.readFully(resp);
            if (resp[0] == 0x01) {
                statusLabel.setText("STATUS: CONNECTED");
                statusLabel.setForeground(Color.GREEN);
            } else {
                statusLabel.setText("STATUS: HANDSHAKE FAIL");
                statusLabel.setForeground(Color.RED);
            }

            setGain(gainSlider.getValue());
            setFrequencyRaw(centerFreq);

        } catch (IOException e) {
            statusLabel.setText("STATUS: NO rtl_tcp ON PORT 1234");
            statusLabel.setForeground(Color.RED);
        }
    }

    private void setFrequency() {
        try {
            double mhz = Double.parseDouble(freqField.getText());
            centerFreq = (long)(mhz * 1e6);
            setFrequencyRaw(centerFreq);
        } catch (Exception e) {}
    }

    private void setFrequencyRaw(double freq) {
        try {
            dos.writeByte(0x02);
            dos.writeInt((int)freq);
            dos.flush();
            freqLabel.setText(String.format("FREQ: %.3f MHz", freq / 1e6));
        } catch (IOException e) {}
    }

    private void setGain(int gain) {
        try {
            dos.writeByte(0x04);
            dos.writeInt(gain);
            dos.flush();
        } catch (IOException e) {}
    }

    private void startCapture() {
        if (dis == null) return;
        Executors.newSingleThreadExecutor().submit(() -> {
            byte[] raw = new byte[BUFFER_SIZE * 2];
            while (running) {
                try {
                    int read = dis.read(raw);
                    if (read > 0) {
                        for (int i = 0; i < read / 2 && bufferPos < BUFFER_SIZE * 2; i++) {
                            byte I = raw[i*2];
                            byte Q = raw[i*2+1];
                            iqBuffer[bufferPos++] = (I / 128.0f);
                            iqBuffer[bufferPos++] = (Q / 128.0f);
                            if (bufferPos >= BUFFER_SIZE * 2) bufferPos = 0;
                        }
                    }
                } catch (IOException e) {
                    running = false;
                    statusLabel.setText("STATUS: LOST CONNECTION");
                }
            }
        });
    }

    private void processFFT() {
        if (bufferPos < FFT_SIZE) return;
        float gain = gainSlider.getValue() / 20.0f;

        for (int i = 0; i < FFT_SIZE; i++) {
            int idx = (bufferPos - FFT_SIZE*2 + i*2) % (BUFFER_SIZE*2);
            if (idx < 0) idx += BUFFER_SIZE*2;
            real[i] = iqBuffer[idx] * gain;
            imag[i] = iqBuffer[idx+1] * gain;
        }

        windowFunction(real, imag);
        fft(real, imag);

        double totalPower = 0;
        double maxPower = 0;
        int maxBin = 0;

        for (int i = 0; i < FFT_SIZE/2; i++) {
            double mag = Math.hypot(real[i], imag[i]);
            double power = 20 * Math.log10(mag + 1e-10);
            spectrum[i] = (int)(Math.min(300, (power + 100) * 3));
            totalPower += power;
            if (power > maxPower && i > 5 && i < FFT_SIZE/2 - 5) {
                maxPower = power;
                maxBin = i;
            }
        }

        avgPower = totalPower / (FFT_SIZE/2);
        powerLabel.setText(String.format("PWR: %.1f dBm", avgPower));

        double noiseSum = 0;
        for (int i = 10; i < 100; i++) noiseSum += 20 * Math.log10(Math.hypot(real[i], imag[i]) + 1e-10);
        noiseFloor = noiseSum / 90;
        noiseLabel.setText(String.format("NOISE: %.1f dB", noiseFloor));

        double offset = (maxBin * (double)SAMPLE_RATE) / FFT_SIZE;
        peakFreq = centerFreq - SAMPLE_RATE/2 + offset;

        repaint();
    }

    private void windowFunction(float[] r, float[] im) {
        for (int i = 0; i < FFT_SIZE; i++) {
            double hann = 0.5 * (1 - Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
            r[i] *= hann;
            im[i] *= hann;
        }
    }

    private void fft(float[] r, float[] im) {
        int n = FFT_SIZE;
        int bits = 0;
        while ((1 << bits) < n) bits++;

        for (int i = 0; i < n; i++) {
            int rev = 0;
            for (int j = 0; j < bits; j++) {
                rev = (rev << 1) | ((i >> j) & 1);
            }
            if (i < rev) {
                float tr = r[i]; r[i] = r[rev]; r[rev] = tr;
                float ti = im[i]; im[i] = im[rev]; im[rev] = ti;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            float angle = (float)(-2 * Math.PI / len);
            float wrlen = (float)Math.cos(angle);
            float wilen = (float)Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float wr = 1;
                float wi = 0;
                for (int j = 0; j < len/2; j++) {
                    float ur = r[i+j];
                    float ui = im[i+j];
                    float vr = r[i+j+len/2] * wr - im[i+j+len/2] * wi;
                    float vi = r[i+j+len/2] * wi + im[i+j+len/2] * wr;
                    r[i+j] = ur + vr;
                    im[i+j] = ui + vi;
                    r[i+j+len/2] = ur - vr;
                    im[i+j+len/2] = ui - vi;
                    float nwr = wr * wrlen - wi * wilen;
                    float nwi = wr * wilen + wi * wrlen;
                    wr = nwr;
                    wi = nwi;
                }
            }
        }
    }

    @Override
    public void run() {
        while (running) {
            processFFT();
            try { Thread.sleep(50); } catch (Exception e) {}
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, WIDTH, HEIGHT);

        g2.setColor(new Color(0, 255, 0, 60));
        for (int y = 0; y < HEIGHT; y += 40) {
            g2.drawLine(0, y, WIDTH, y);
        }

        g2.setColor(Color.GREEN);
        for (int x = 0; x < WIDTH; x++) {
            int idx = (x * (FFT_SIZE/2)) / WIDTH;
            if (idx < FFT_SIZE/2) {
                int h = Math.min(HEIGHT - 80, spectrum[idx]);
                g2.drawLine(x, HEIGHT - 50, x, HEIGHT - 50 - h);
            }
        }

        int peakX = (int)(((peakFreq - (centerFreq - SAMPLE_RATE/2)) / SAMPLE_RATE) * WIDTH);
        if (peakX > 0 && peakX < WIDTH) {
            g2.setColor(Color.RED);
            g2.drawLine(peakX, HEIGHT - 200, peakX, HEIGHT - 50);
            g2.drawString("^ PEAK", peakX - 10, HEIGHT - 210);
        }

        g2.setColor(Color.CYAN);
        g2.setFont(new Font("Monospaced", Font.BOLD, 13));
        g2.drawString("╔══════════════════════════════════════════════════════╗", 20, 30);
        g2.drawString("║  REAL SDR SPECTRUM ANALYZER - I/Q CAPTURE v1.0      ║", 20, 50);
        g2.drawString("╚══════════════════════════════════════════════════════╝", 20, 70);

        g2.setFont(new Font("Monospaced", Font.PLAIN, 11));
        g2.drawString("┌────────────────────────────────────────────────────┐", 20, 95);
        g2.drawString("│ [SAMPLE RATE] 2.4 MSPS  │ [FFT SIZE] 4096           │", 20, 110);
        g2.drawString("│ [BANDWIDTH] ~2.4 MHz    │ [DEMOD] I/Q Raw           │", 20, 125);
        g2.drawString("└────────────────────────────────────────────────────┘", 20, 140);

        g2.setColor(Color.YELLOW);
        g2.drawString(String.format("CENTER: %.6f MHz | PEAK: %.6f MHz | SPAN: %.1f MHz",
            centerFreq/1e6, peakFreq/1e6, SAMPLE_RATE/1e6), 20, HEIGHT - 20);

        g2.setColor(Color.GREEN);
        g2.drawString("<<< by @console_hack | REAL SDR ENGINE ACTIVE >>>", WIDTH - 380, HEIGHT - 15);

        g2.setColor(Color.WHITE);
        g2.drawRect(5, 5, WIDTH - 10, HEIGHT - 10);
        g2.drawRect(6, 6, WIDTH - 12, HEIGHT - 12);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e) {}
            new RealSDRVisualizer();
        });
    }
}
