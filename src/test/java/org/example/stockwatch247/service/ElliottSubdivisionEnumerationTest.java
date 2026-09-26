package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ElliottSubdivisionEnumerationTest {
    @Test void earlyContainmentPruningMatchesExhaustiveEnumeration() throws Exception {
        var detector = new ElliottWaveDetectionService();
        Class<?> pivot = Class.forName(ElliottWaveDetectionService.class.getName()+"$Pivot");
        Class<?> type = Class.forName(ElliottWaveDetectionService.class.getName()+"$PivotType");
        var constructor = pivot.getDeclaredConstructor(int.class,type,double.class); constructor.setAccessible(true);
        Object low = Arrays.stream(type.getEnumConstants()).filter(v -> v.toString().equals("LOW")).findFirst().orElseThrow();
        Object high = Arrays.stream(type.getEnumConstants()).filter(v -> v.toString().equals("HIGH")).findFirst().orElseThrow();
        var select = ElliottWaveDetectionService.class.getDeclaredMethod("selectDegreePivots",List.class,Array.newInstance(type,0).getClass(),pivot,pivot,int.class); select.setAccessible(true);
        var coherent = ElliottWaveDetectionService.class.getDeclaredMethod("isScaleCoherentGrouping",List.class,List.class); coherent.setAccessible(true);
        var random = new Random(240926);
        for (int sample=0;sample<100;sample++) for (int size : new int[]{2,4}) {
            var interior = new ArrayList<Object>();
            for (int i=1;i<=14;i++) interior.add(constructor.newInstance(i,i%2==1?high:low,80+random.nextDouble()*40));
            Object start=constructor.newInstance(0,low,90.0), end=constructor.newInstance(15,high,110.0);
            Object types=Array.newInstance(type,size);
            for(int i=0;i<size;i++) Array.set(types,i,i%2==0?high:low);
            List<List<Object>> brute=new ArrayList<>(); enumerate(interior,0,size,new ArrayList<>(),brute);
            List<List<Object>> expected=new ArrayList<>();
            for(var chosen:brute) {
                var sequence=new ArrayList<>(); sequence.add(start); sequence.addAll(chosen); sequence.add(end);
                if((boolean)coherent.invoke(detector,interior,sequence)) expected.add(chosen);
            }
            assertThat(select.invoke(detector,interior,types,start,end,Integer.MAX_VALUE)).isEqualTo(expected);
        }
    }
    private void enumerate(List<Object> interior,int from,int count,List<Object> chosen,List<List<Object>> result) {
        if(chosen.size()==count) { result.add(List.copyOf(chosen)); return; }
        for(int i=from;i<interior.size();i++) {
            if(i%2!=chosen.size()%2) continue;
            chosen.add(interior.get(i)); enumerate(interior,i+1,count,chosen,result); chosen.removeLast();
        }
    }
}
