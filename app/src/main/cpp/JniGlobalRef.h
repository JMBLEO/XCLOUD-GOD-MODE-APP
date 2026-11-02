#pragma once
#include <jni.h>

class JniGlobalRef {
private:
    jobject obj_;
    JNIEnv* env_;

public:
    JniGlobalRef(JNIEnv* env, jobject obj) : env_(env) {
        if (obj != nullptr) {
            obj_ = env->NewGlobalRef(obj);
        } else {
            obj_ = nullptr;
        }
    }

    ~JniGlobalRef() {
        if (obj_ != nullptr && env_ != nullptr) {
            env_->DeleteGlobalRef(obj_);
        }
    }

    // Disable copy
    JniGlobalRef(const JniGlobalRef&) = delete;
    JniGlobalRef& operator=(const JniGlobalRef&) = delete;

    // Enable move
    JniGlobalRef(JniGlobalRef&& other) noexcept : obj_(other.obj_), env_(other.env_) {
        other.obj_ = nullptr;
    }

    JniGlobalRef& operator=(JniGlobalRef&& other) noexcept {
        if (this != &other) {
            if (obj_ != nullptr && env_ != nullptr) {
                env_->DeleteGlobalRef(obj_);
            }
            obj_ = other.obj_;
            env_ = other.env_;
            other.obj_ = nullptr;
        }
        return *this;
    }

    jobject get() const { return obj_; }
    operator jobject() const { return obj_; }
};
