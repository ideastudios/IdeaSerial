/*
 * Copyright 2009-2011 Cedric Priscal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <termios.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <string.h>
#include <jni.h>

#include "SerialPort.h"

#include "android/log.h"
static const char *TAG="serial_port";
#define LOGI(fmt, args...) __android_log_print(ANDROID_LOG_INFO,  TAG, fmt, ##args)
#define LOGD(fmt, args...) __android_log_print(ANDROID_LOG_DEBUG, TAG, fmt, ##args)
#define LOGE(fmt, args...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##args)

/* 与 usb-serial-for-android 的 Parity 常量保持一致 */
#define PARITY_NONE  0
#define PARITY_ODD   1
#define PARITY_EVEN  2
#define PARITY_MARK  3
#define PARITY_SPACE 4

#define FLOWCTRL_NONE     0
#define FLOWCTRL_RTSCTS   1
#define FLOWCTRL_XONXOFF  2

static speed_t getBaudrate(jint baudrate)
{
    switch(baudrate) {
        case 0: return B0;
        case 50: return B50;
        case 75: return B75;
        case 110: return B110;
        case 134: return B134;
        case 150: return B150;
        case 200: return B200;
        case 300: return B300;
        case 600: return B600;
        case 1200: return B1200;
        case 1800: return B1800;
        case 2400: return B2400;
        case 4800: return B4800;
        case 9600: return B9600;
        case 19200: return B19200;
        case 38400: return B38400;
        case 57600: return B57600;
        case 115200: return B115200;
        case 230400: return B230400;
        case 460800: return B460800;
        case 500000: return B500000;
        case 576000: return B576000;
        case 921600: return B921600;
        case 1000000: return B1000000;
        case 1152000: return B1152000;
        case 1500000: return B1500000;
        case 2000000: return B2000000;
        case 2500000: return B2500000;
        case 3000000: return B3000000;
        case 3500000: return B3500000;
        case 4000000: return B4000000;
        default: return -1;
    }
}

/* 数据位: 5/6/7/8 */
static void setDataBits(struct termios *cfg, jint databits)
{
    cfg->c_cflag &= ~CSIZE;
    switch (databits) {
        case 5: cfg->c_cflag |= CS5; break;
        case 6: cfg->c_cflag |= CS6; break;
        case 7: cfg->c_cflag |= CS7; break;
        default: cfg->c_cflag |= CS8; break;
    }
}

/* 停止位: 1/2 */
static void setStopBits(struct termios *cfg, jint stopbits)
{
    if (stopbits == 2) {
        cfg->c_cflag |= CSTOPB;
    } else {
        cfg->c_cflag &= ~CSTOPB;
    }
}

/* 校验位: 0=无 1=奇 2=偶 3=标 4=空 */
static void setParity(struct termios *cfg, jint parity)
{
    /* 先全部清掉 */
    cfg->c_cflag &= ~(PARENB | PARODD);
#ifdef CMSPAR
    cfg->c_cflag &= ~CMSPAR;
#endif
    cfg->c_iflag &= ~(INPCK | ISTRIP);

    switch (parity) {
        case PARITY_ODD:
            cfg->c_cflag |= (PARENB | PARODD);
            cfg->c_iflag |= INPCK;
            break;
        case PARITY_EVEN:
            cfg->c_cflag |= PARENB;
            cfg->c_iflag |= INPCK;
            break;
#ifdef CMSPAR
        case PARITY_MARK:
            cfg->c_cflag |= (PARENB | PARODD | CMSPAR);
            cfg->c_iflag |= INPCK;
            break;
        case PARITY_SPACE:
            cfg->c_cflag |= PARENB | CMSPAR;
            cfg->c_iflag |= INPCK;
            break;
#endif
        case PARITY_NONE:
        default:
            break;
    }
}

/* 流控: 0=无 1=RTS/CTS 2=XON/XOFF */
static void setFlowControl(struct termios *cfg, jint flowcontrol)
{
    switch (flowcontrol) {
#ifdef CRTSCTS
        case FLOWCTRL_RTSCTS:
            cfg->c_cflag |= CRTSCTS;
            cfg->c_iflag &= ~(IXON | IXOFF);
            break;
#endif
        case FLOWCTRL_XONXOFF:
            cfg->c_iflag |= (IXON | IXOFF);
#ifdef CRTSCTS
            cfg->c_cflag &= ~CRTSCTS;
#endif
            break;
        case FLOWCTRL_NONE:
        default:
            cfg->c_iflag &= ~(IXON | IXOFF);
#ifdef CRTSCTS
            cfg->c_cflag &= ~CRTSCTS;
#endif
            break;
    }
}

/*
 * Class:     com.github.ideastudios.serialport.SerialPort
 * Method:    open
 * Signature: (Ljava/lang/String;IIIIII)Ljava/io/FileDescriptor;
 */
JNIEXPORT jobject JNICALL Java_com_github_ideastudios_serialport_SerialPort_open
        (JNIEnv *env, jclass thiz, jstring path, jint baudrate, jint databits,
         jint stopbits, jint parity, jint flowcontrol, jint flags)
{
    int fd;
    speed_t speed;
    jobject mFileDescriptor;

    /* Check arguments */
    {
        speed = getBaudrate(baudrate);
        if (speed == -1) {
            LOGE("Invalid baudrate");
            return NULL;
        }
    }

    /* Opening device */
    {
        jboolean iscopy;
        const char *path_utf = (*env)->GetStringUTFChars(env, path, &iscopy);
        LOGD("Opening serial port %s with flags 0x%x", path_utf, O_RDWR | flags);
        fd = open(path_utf, O_RDWR | flags);
        LOGD("open() fd = %d", fd);
        (*env)->ReleaseStringUTFChars(env, path, path_utf);
        if (fd == -1)
        {
            LOGE("Cannot open port");
            return NULL;
        }
    }

    /* Configure device */
    {
        struct termios cfg;
        LOGD("Configuring serial port");
        if (tcgetattr(fd, &cfg))
        {
            LOGE("tcgetattr() failed");
            close(fd);
            return NULL;
        }

        cfmakeraw(&cfg);
        cfsetispeed(&cfg, speed);
        cfsetospeed(&cfg, speed);

        setDataBits(&cfg, databits);
        setStopBits(&cfg, stopbits);
        setParity(&cfg, parity);
        setFlowControl(&cfg, flowcontrol);

        if (tcsetattr(fd, TCSANOW, &cfg))
        {
            LOGE("tcsetattr() failed");
            close(fd);
            return NULL;
        }
    }

    /* Create a corresponding file descriptor */
    {
        jclass cFileDescriptor = (*env)->FindClass(env, "java/io/FileDescriptor");
        jmethodID iFileDescriptor = (*env)->GetMethodID(env, cFileDescriptor, "<init>", "()V");
        jfieldID descriptorID = (*env)->GetFieldID(env, cFileDescriptor, "descriptor", "I");
        mFileDescriptor = (*env)->NewObject(env, cFileDescriptor, iFileDescriptor);
        (*env)->SetIntField(env, mFileDescriptor, descriptorID, (jint)fd);
    }

    return mFileDescriptor;
}

/*
 * Class:     com.github.ideastudios.serialport.SerialPort
 * Method:    close
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_com_github_ideastudios_serialport_SerialPort_close
        (JNIEnv *env, jobject thiz)
{
    jclass SerialPortClass = (*env)->GetObjectClass(env, thiz);
    jclass FileDescriptorClass = (*env)->FindClass(env, "java/io/FileDescriptor");

    jfieldID mFdID = (*env)->GetFieldID(env, SerialPortClass, "mFd", "Ljava/io/FileDescriptor;");
    jfieldID descriptorID = (*env)->GetFieldID(env, FileDescriptorClass, "descriptor", "I");

    jobject mFd = (*env)->GetObjectField(env, thiz, mFdID);
    jint descriptor = (*env)->GetIntField(env, mFd, descriptorID);

    LOGD("close(fd = %d)", descriptor);
    close(descriptor);
}
