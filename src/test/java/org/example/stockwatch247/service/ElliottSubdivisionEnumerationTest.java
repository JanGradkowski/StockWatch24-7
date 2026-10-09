package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ElliottSubdivisionEnumerationTest {
    @Test void incrementalPivotTrajectoriesMatchIndependentPrefixScans() throws Exception {
        var detector = new ElliottWaveDetectionService();
        var compute = ElliottWaveDetectionService.class.getDeclaredMethod("computePivots", List.class, double.class);
        compute.setAccessible(true);
        var scoped = ElliottWaveDetectionService.class.getDeclaredMethod("withSubdivisionMemo", java.util.function.Supplier.class);
        scoped.setAccessible(true);
        var random = new Random(300930);
        var candles = new ArrayList<org.example.stockwatch247.model.EnrichedCandle>();
        double close = 100;
        for (int i=0;i<180;i++) {
            double open=close; close += random.nextDouble()*8-4;
            candles.add(new org.example.stockwatch247.model.EnrichedCandle((long)i,open,
                    Math.max(open,close)+random.nextDouble()*3,Math.min(open,close)-random.nextDouble()*3,close,
                    100,100,Double.NaN,Double.NaN,Double.NaN,Double.NaN,i%7==0?Double.NaN:2.0,Double.NaN));
        }
        var window=ValidatedCandleWindow.latest(candles);
        for(double sensitivity:new double[]{.75,1.25,2,3}) {
            var expected=new HashMap<String,Object>();
            for(int from=0;from<50;from+=3) for(int end=from+3;end<=candles.size();end+=7)
                expected.put(from+":"+end,compute.invoke(detector,candles.subList(from,end),sensitivity));
            scoped.invoke(detector,(java.util.function.Supplier<Void>)()-> {
                try {
                    // Reverse visits force historical reads after a trajectory has advanced.
                    for(int from=0;from<50;from+=3) for(int end=candles.size();end>=from+3;end--)
                        if(expected.containsKey(from+":"+end)) assertThat(compute.invoke(detector,window.subList(from,end),sensitivity))
                                .isEqualTo(expected.get(from+":"+end));
                } catch(ReflectiveOperationException e) { throw new RuntimeException(e); }
                return null;
            });
        }
    }

    @Test void parentEndpointPruningPreservesCompleteReferenceGeometry() throws Exception {
        var detector = new ElliottWaveDetectionService();
        Class<?> pivot = Class.forName(ElliottWaveDetectionService.class.getName()+"$Pivot");
        Class<?> type = Class.forName(ElliottWaveDetectionService.class.getName()+"$PivotType");
        var constructor = pivot.getDeclaredConstructor(int.class,type,double.class); constructor.setAccessible(true);
        Object low = Arrays.stream(type.getEnumConstants()).filter(v -> v.toString().equals("LOW")).findFirst().orElseThrow();
        Object high = Arrays.stream(type.getEnumConstants()).filter(v -> v.toString().equals("HIGH")).findFirst().orElseThrow();
        var extension = ElliottWaveDetectionService.class.getDeclaredMethod("parentExtensionCanComplete",List.class,pivot);
        extension.setAccessible(true);
        var random = new Random(300926);
        double[] base = {100,120,110,145,130,160,140,150,130};
        int impulses=0, corrections=0;
        for (boolean bullish : new boolean[]{true,false}) {
            String direction = bullish ? "Bullish" : "Bearish";
            var impulse = ElliottWaveDetectionService.class.getDeclaredMethod("is"+direction+"ImpulseComplete",
                    pivot,pivot,pivot,pivot,pivot,pivot); impulse.setAccessible(true);
            var correction = ElliottWaveDetectionService.class.getDeclaredMethod("is"+direction+"CorrectionComplete",List.class);
            correction.setAccessible(true);
            for(int sample=0;sample<2000;sample++) {
                var sequence=new ArrayList<Object>();
                for(int i=0;i<base.length;i++) {
                    double price=base[i]+random.nextDouble()*24-12;
                    sequence.add(constructor.newInstance(i*10,(i%2==0)==bullish?low:high,bullish?price:300-price));
                }
                if((boolean)impulse.invoke(detector,sequence.subList(0,6).toArray())) {
                    impulses++;
                    for(int i=2;i<6;i++) assertThat(extension.invoke(detector,sequence.subList(0,i),sequence.get(i))).isEqualTo(true);
                }
                if((boolean)correction.invoke(detector,sequence)) {
                    corrections++;
                    for(int i=6;i<9;i++) assertThat(extension.invoke(detector,sequence.subList(0,i),sequence.get(i))).isEqualTo(true);
                }
            }
        }
        assertThat(impulses).isGreaterThan(100);
        assertThat(corrections).isGreaterThan(100);
    }

    @Test void earlyContainmentPruningMatchesExhaustiveEnumeration() throws Exception {
        var detector = new ElliottWaveDetectionService();
        Class<?> pivot = Class.forName(ElliottWaveDetectionService.class.getName()+"$Pivot");
        Class<?> type = Class.forName(ElliottWaveDetectionService.class.getName()+"$PivotType");
        var constructor = pivot.getDeclaredConstructor(int.class,type,double.class); constructor.setAccessible(true);
        Object low = Arrays.stream(type.getEnumConstants()).filter(v -> v.toString().equals("LOW")).findFirst().orElseThrow();
        Object high = Arrays.stream(type.getEnumConstants()).filter(v -> v.toString().equals("HIGH")).findFirst().orElseThrow();
        var select = ElliottWaveDetectionService.class.getDeclaredMethod("selectDegreePivots",List.class,Array.newInstance(type,0).getClass(),pivot,pivot,int.class); select.setAccessible(true);
        var coherent = ElliottWaveDetectionService.class.getDeclaredMethod("isScaleCoherentGrouping",List.class,List.class); coherent.setAccessible(true);
        var motiveSelect = ElliottWaveDetectionService.class.getDeclaredMethod("selectDegreePivots",List.class,Array.newInstance(type,0).getClass(),pivot,pivot,int.class,boolean.class,boolean.class); motiveSelect.setAccessible(true);
        var standard = ElliottWaveDetectionService.class.getDeclaredMethod("passesFractalMotiveHardRules",List.class,boolean.class); standard.setAccessible(true);
        var diagonal = ElliottWaveDetectionService.class.getDeclaredMethod("isDiagonalSubdivision",List.class,boolean.class); diagonal.setAccessible(true);
        var random = new Random(240926);
        for (int sample=0;sample<200;sample++) for (int size : new int[]{2,4}) {
            boolean rising = sample % 2 == 0;
            var interior = new ArrayList<Object>();
            int interiorCount = sample % 4 < 2 ? 14 : 32;
            for (int i=1;i<=interiorCount;i++) interior.add(constructor.newInstance(i,(i%2==1)==rising?high:low,80+random.nextDouble()*40));
            Object start=constructor.newInstance(0,rising?low:high,rising?90.0:110.0);
            Object end=constructor.newInstance(interiorCount+1,rising?high:low,rising?110.0:90.0);
            Object types=Array.newInstance(type,size);
            for(int i=0;i<size;i++) Array.set(types,i,(i%2==0)==rising?high:low);
            List<List<Object>> brute=new ArrayList<>(); enumerate(interior,0,size,new ArrayList<>(),brute);
            List<List<Object>> expected=new ArrayList<>();
            for(var chosen:brute) {
                var sequence=new ArrayList<>(); sequence.add(start); sequence.addAll(chosen); sequence.add(end);
                if((boolean)coherent.invoke(detector,interior,sequence)) expected.add(chosen);
            }
            assertThat(select.invoke(detector,interior,types,start,end,Integer.MAX_VALUE)).isEqualTo(expected);
            if(size==4) for(boolean allowDiagonal:new boolean[]{false,true}) {
                var motiveExpected=new ArrayList<List<Object>>();
                for(var chosen:expected) {
                    var sequence=new ArrayList<>();sequence.add(start);sequence.addAll(chosen);sequence.add(end);
                    if((boolean)standard.invoke(detector,sequence,rising)
                            || allowDiagonal && (boolean)diagonal.invoke(detector,sequence,rising)) motiveExpected.add(chosen);
                }
                assertThat(motiveSelect.invoke(detector,interior,types,start,end,Integer.MAX_VALUE,true,allowDiagonal)).isEqualTo(motiveExpected);
            }
        }
        // A later, much larger endpoint increases the existing relative tolerance.
        // Nearby prices on both sides of the origin must not prematurely stop its scan.
        var wide = List.of(constructor.newInstance(1,high,101.0), constructor.newInstance(2,low,99.0),
                constructor.newInstance(3,high,1.0e12), constructor.newInstance(4,low,100.0));
        Object types=Array.newInstance(type,2); Array.set(types,0,high); Array.set(types,1,low);
        var actual=(List<?>)select.invoke(detector,wide,types,
                constructor.newInstance(0,low,100.0),constructor.newInstance(5,high,1.0e12),Integer.MAX_VALUE);
        assertThat(actual.contains(List.of(wide.get(2),wide.get(3)))).isTrue();
    }
    private void enumerate(List<Object> interior,int from,int count,List<Object> chosen,List<List<Object>> result) {
        if(chosen.size()==count) { result.add(List.copyOf(chosen)); return; }
        for(int i=from;i<interior.size();i++) {
            if(i%2!=chosen.size()%2) continue;
            chosen.add(interior.get(i)); enumerate(interior,i+1,count,chosen,result); chosen.removeLast();
        }
    }
}
