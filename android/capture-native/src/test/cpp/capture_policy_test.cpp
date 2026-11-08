#include "../../main/cpp/capture_policy.h"
#include <cmath>
#include <iostream>
#include <set>
#include <vector>

using uwu::MouseMotionBatcher;
using uwu::RawMouseSample;
void check(bool value,const char* message) { if (!value) throw std::runtime_error(message); }

uwu::DeviceRoles roles(unsigned bus,std::initializer_list<unsigned> keys,std::initializer_list<unsigned> relative={},std::initializer_list<unsigned> absolute={}) {
    const std::set<unsigned> k(keys),r(relative),a(absolute);
    return uwu::classifyDevice(bus,[&](unsigned code){return k.count(code)!=0;},
        [&](unsigned code){return r.count(code)!=0;},[&](unsigned code){return a.count(code)!=0;});
}

void compactAndProgrammableKeyboardsNeedNoQwertySignature() {
    check(roles(3,{17,30,31,32}).keyboard,"gaming keypad must be a keyboard");
    check(roles(3,{79,80,81,96}).keyboard,"numeric keypad must be a keyboard");
    check(roles(5,{183}).keyboard,"Bluetooth macro pad must be a keyboard");
    check(roles(3,{656}).keyboard,"extended macro key must be a keyboard");
}
void consumerInterfacesRemainKeyboardButPowerOnlyIsExcluded() {
    check(roles(3,{113,114,115,164}).keyboard,"consumer controls must be retained");
    check(!roles(3,{116,142,143,205,408}).keyboard,"power-only interface must stay with Android");
    check(!roles(3,{240}).keyboard,"unknown capability must not classify a keyboard");
}
void relativeAndSplitButtonMiceAreRecognizedWithoutVendorNames() {
    check(roles(3,{}, {0,1}).mouse,"relative-only mouse interface must be retained");
    check(roles(5,{0x110}).mouse,"split mouse buttons must be retained");
    const auto mixed=roles(3,{0x110,17,30},{0,1});
    check(mixed.mouse && mixed.keyboard,"mixed USB interface must preserve both roles");
}
void touchInternalAndGamepadDevicesStayExcluded() {
    const auto touch=roles(3,{30,0x110},{0,1},{0x35,0x36});
    check(!touch.mouse && !touch.keyboard,"touchscreen must never be grabbed");
    const auto internal=roles(0x19,{30,0x110},{0,1});
    check(!internal.mouse && !internal.keyboard,"internal device must stay excluded");
    const auto gamepad=roles(3,{1,0x130},{},{0,1});
    check(!gamepad.mouse && !gamepad.keyboard,"gamepad's optional Escape is not a keyboard");
    const auto relativeGamepad=roles(3,{30,0x130},{0,1});
    check(!relativeGamepad.mouse && !relativeGamepad.keyboard,"gamepad's relative axes do not authorize capture");
    check(!roles(3,{0x110},{},{0,1}).mouse,"absolute tablet is not a relative mouse");
}
void keyboardRangesExcludeButtonsAndRetainExtendedKeys() {
    for (unsigned code:{1U,115U,0x160U,0x218U,0x230U,0x2e8U}) check(uwu::keyboardCode(code),"keyboard range missing");
    for (unsigned code:{0U,0x100U,0x110U,0x130U,0x220U,0x2c0U,0x300U}) check(!uwu::keyboardCode(code),"button must not become key");
}
void eightKilohertzLimitedToOneHundredKeepsEveryOriginalSample() {
    MouseMotionBatcher batcher(100);
    size_t expected=0,deliveries=0;
    double x=0,y=0,wheel=0;
    const auto emit=[&](const RawMouseSample* samples,size_t count) {
        ++deliveries;
        for (size_t index=0;index<count;++index) {
            check(samples[index].time==static_cast<int64_t>(expected)*125'000,"source timestamp/order changed");
            check(samples[index].slot==static_cast<int>(expected%2),"device slot changed");
            x+=samples[index].dx;y+=samples[index].dy;wheel+=samples[index].wheel;++expected;
        }
        return true;
    };
    for (size_t index=0;index<8000;++index) {
        const int64_t source=static_cast<int64_t>(index)*125'000;
        check(batcher.offer({static_cast<int>(index%2),source,.125f,-.25f,index%1000==0?1.f:0.f,0.f},source,emit),"capture overflowed");
    }
    check(batcher.flushDue(1'000'000'000LL,emit),"final timer failed");
    check(expected==8000 && deliveries==101,"rate or retained sample count wrong");
    check(x==1000 && y==-2000 && wheel==8,"movement/wheel was lost");
    check(batcher.maxBatchSamples.load()<=80,"batch grew beyond the 8kHz/100Hz interval");
    check(batcher.waitNanos(1'000'000'000LL)==-1,"idle capture must not run a timer");
}
void allSelectedRatesBoundTransportAndNeverInventPhysicalSamples() {
    for (int rate:{100,500,1000}) {
        MouseMotionBatcher batcher(rate);size_t delivered=0,batches=0;
        const auto emit=[&](const RawMouseSample*,size_t count){delivered+=count;++batches;return true;};
        for (int index=0;index<8000;++index) check(batcher.offer({0,index*125'000LL,1,0,0,0},index*125'000LL,emit),"rate capture failed");
        check(batcher.flushDue(1'000'000'000LL,emit),"tail did not flush");
        check(delivered==8000 && batches==static_cast<size_t>(rate+1),"configured rate not applied before transport");
    }
    MouseMotionBatcher slow(1000);size_t emitted=0;
    const auto emit=[&](const RawMouseSample*,size_t count){emitted+=count;return true;};
    for (int index=0;index<125;++index) check(slow.offer({0,index*8'000'000LL,1,0,0,0},index*8'000'000LL,emit),"slow mouse failed");
    check(emitted==125,"limiter invented or delayed already sparse samples");
}
void buttonAndKeyBarriersFlushBeforeTheEdgeWithoutWaiting() {
    MouseMotionBatcher batcher(100);std::vector<int> order;
    const auto emit=[&](const RawMouseSample*,size_t count){order.push_back(static_cast<int>(count));return true;};
    check(batcher.offer({0,0,1,0,0,0},0,emit),"first move failed");
    check(batcher.offer({0,1'000'000,2,0,0,0},1'000'000,emit),"buffered move failed");
    check(order==std::vector<int>{1},"rate limit did not hold the movement");
    check(batcher.flush(1'000'001,emit),"barrier failed");order.push_back(-1);
    check(order==std::vector<int>({1,1,-1}),"key/button overtook motion");
    check(batcher.barrierFlushes.load()==1,"barrier accounting wrong");
}
void unrestrictedModeFlushesOnlyAlreadyAvailableWorkWithoutATimer() {
    MouseMotionBatcher batcher;size_t calls=0,raw=0;
    const auto emit=[&](const RawMouseSample*,size_t count){++calls;raw+=count;return true;};
    for (int index=0;index<20;++index) check(batcher.offer({0,index,1,0,0,0},index,emit),"free input failed");
    check(calls==0 && batcher.waitNanos(20)==-1,"free mode introduced a cadence/timer");
    check(batcher.flush(20,emit,false),"read-end flush failed");
    check(calls==1 && raw==20,"read batch did not preserve samples");
}
void reconfigurationFlushesOldSamplesWithoutDiscardOrReplayingEdges() {
    MouseMotionBatcher batcher(100);std::vector<int64_t> times;
    const auto emit=[&](const RawMouseSample* data,size_t count){for(size_t index=0;index<count;++index) times.push_back(data[index].time);return true;};
    check(batcher.offer({0,0,1,0,0,0},0,emit),"first failed");
    check(batcher.offer({0,1'000'000,1,0,0,0},1'000'000,emit),"second failed");
    check(batcher.configure(500,1'000'001,emit),"configure failed");
    check(times==std::vector<int64_t>({0,1'000'000}),"configuration lost/replayed pending movement");
    check(batcher.rate()==500 && batcher.pendingSamples.load()==0,"configuration did not take effect");
    check(!batcher.configure(1001,1'000'002,emit) && batcher.rate()==500,"invalid rate changed state");
}
void stopAndConsumerFailureNeverReplayPendingMovement() {
    MouseMotionBatcher batcher(100);size_t calls=0;
    const auto emit=[&](const RawMouseSample*,size_t){++calls;return true;};
    check(batcher.offer({0,0,1,0,0,0},0,emit),"first failed");
    check(batcher.offer({0,1'000'000,1,0,0,0},1'000'000,emit),"second failed");
    batcher.discard();check(batcher.flushDue(20'000'000,emit),"discard flush failed");
    check(calls==1 && batcher.discardedSamples.load()==1,"stop replayed movement");
    check(!batcher.offer({0,30'000'000,1,0,0,0},30'000'000,[](const RawMouseSample*,size_t){return false;}),"consumer rejection lost");
    check(batcher.pendingSamples.load()==0,"failed batch could replay");
}
void boundedCapacityFailsInsteadOfDroppingRawPrecisionSamples() {
    MouseMotionBatcher batcher;const auto emit=[](const RawMouseSample*,size_t){return true;};
    for (size_t index=0;index<MouseMotionBatcher::kCapacity;++index) check(batcher.offer({0,static_cast<int64_t>(index),1,0,0,0},index,emit),"capacity too small");
    check(!batcher.offer({0,999,1,0,0,0},999,emit),"overflow silently discarded or exceeded capacity");
    check(batcher.rawSamples.load()==MouseMotionBatcher::kCapacity,"failed append altered accepted count");
}

int main() {
    try {
        compactAndProgrammableKeyboardsNeedNoQwertySignature();
        consumerInterfacesRemainKeyboardButPowerOnlyIsExcluded();
        relativeAndSplitButtonMiceAreRecognizedWithoutVendorNames();
        touchInternalAndGamepadDevicesStayExcluded();
        keyboardRangesExcludeButtonsAndRetainExtendedKeys();
        eightKilohertzLimitedToOneHundredKeepsEveryOriginalSample();
        allSelectedRatesBoundTransportAndNeverInventPhysicalSamples();
        buttonAndKeyBarriersFlushBeforeTheEdgeWithoutWaiting();
        unrestrictedModeFlushesOnlyAlreadyAvailableWorkWithoutATimer();
        reconfigurationFlushesOldSamplesWithoutDiscardOrReplayingEdges();
        stopAndConsumerFailureNeverReplayPendingMovement();
        boundedCapacityFailsInsteadOfDroppingRawPrecisionSamples();
        std::cout << "{\"tests\":12,\"failures\":0}\n";
        return 0;
    } catch(const std::exception& error) {
        std::cerr << "Native capture policy failure: " << error.what() << '\n';return 1;
    }
}
