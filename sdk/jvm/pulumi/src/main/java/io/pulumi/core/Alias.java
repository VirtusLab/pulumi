package io.pulumi.core;

import java.util.Optional;

import io.pulumi.resources.Resource;

public class Alias {
   public Optional<String> urn;
   public Optional<Input<String>> name;
   public Optional<Input<String>> type;
   public Optional<Input<String>> project;
   public Optional<Input<String>> stack;
   public Optional<Resource> parent;
   public Optional<Input<String>> parentUrn;
   public boolean noParent;
   public Alias(Optional<String> urn, Optional<Input<String>> name, Optional<Input<String>> type, Optional<Input<String>>project, Optional<Input<String>> stack, Optional<Resource> parent, Optional<Input<String>> parentUrn, boolean noParent) {
      this.urn = urn;
      this.name = name;
      this.type = type;
      this.project = project;
      this.stack = stack;
      this.parent = parent;
      this.parentUrn = parentUrn;
      this.noParent = noParent;
   }
   public static Alias fromUrn(String urn) {
      return new Alias(Optional.of(urn), Optional.<Input<String>>empty(), Optional.<Input<String>>empty(), Optional.<Input<String>>empty(), Optional.<Input<String>>empty(), Optional.<Resource>empty(), Optional.<Input<String>>empty(), false);
   }
}
